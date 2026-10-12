package observability

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func TestMetricLabelsAreBoundedAndRecoveryIsCounted(t *testing.T) {
	gin.SetMode(gin.TestMode)
	m := New(nil)
	router := gin.New()
	router.Use(m.Middleware(), gin.CustomRecoveryWithWriter(io.Discard, func(c *gin.Context, _ any) { c.AbortWithStatus(http.StatusInternalServerError) }))
	router.GET("/transactions/:id", func(c *gin.Context) { panic("private panic message") })
	for _, req := range []*http.Request{
		httptest.NewRequest(http.MethodGet, "/transactions/private-id?token=private-token", nil),
		httptest.NewRequest("PRIVATE-METHOD-1", "/private-url-1", nil),
		httptest.NewRequest("PRIVATE-METHOD-2", "/private-url-2", nil),
	} {
		router.ServeHTTP(httptest.NewRecorder(), req)
	}
	response := httptest.NewRecorder()
	m.Handler().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	body := response.Body.String()
	for _, secret := range []string{"private-id", "private-token", "PRIVATE-METHOD", "private-url", "private panic"} {
		if strings.Contains(body, secret) {
			t.Fatalf("private value leaked: %s", secret)
		}
	}
	for _, expected := range []string{
		`moneyflow_http_requests_total{method="GET",route="/transactions/:id",status="500"} 1`,
		`moneyflow_http_requests_total{method="OTHER",route="unmatched",status="404"} 2`,
		`moneyflow_http_request_duration_seconds_count{method="GET",route="/transactions/:id"} 1`,
		"go_goroutines", "moneyflow_database_connections_in_use 0", "moneyflow_database_ready 0",
	} {
		if !strings.Contains(body, expected) {
			t.Fatalf("metric missing: %s", expected)
		}
	}
	for _, request := range []*http.Request{httptest.NewRequest(http.MethodPost, "/metrics", nil), httptest.NewRequest(http.MethodGet, "/private", nil)} {
		response := httptest.NewRecorder()
		m.Handler().ServeHTTP(response, request)
		if response.Code == http.StatusOK {
			t.Fatal("metrics listener accepted an unsupported endpoint or method")
		}
	}
}

func TestScrapesDoNotProbeDatabase(t *testing.T) {
	connector := &probeConnector{called: make(chan struct{}, 1)}
	db := sql.OpenDB(connector)
	defer db.Close()
	m := New(db)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { m.ProbeDatabase(ctx); close(done) }()
	select {
	case <-connector.called:
	case <-time.After(time.Second):
		t.Fatal("initial database readiness probe not performed")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("database probe did not stop")
	}
	for i := 0; i < 5; i++ {
		response := httptest.NewRecorder()
		m.Handler().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/metrics", nil))
		if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), "moneyflow_database_ready 1") {
			t.Fatal("cached readiness not exported")
		}
	}
	if connector.pings.Load() != 1 || m.checked.Load() == 0 {
		t.Fatal("scraping performed an extra database query or lost probe timestamp")
	}
}

type probeConnector struct {
	pings  atomic.Int64
	called chan struct{}
}

func (p *probeConnector) Connect(context.Context) (driver.Conn, error) { return &probeConn{p}, nil }
func (p *probeConnector) Driver() driver.Driver                        { return probeDriver{} }

type probeDriver struct{}

func (probeDriver) Open(string) (driver.Conn, error) { return nil, errors.New("unused") }

type probeConn struct{ connector *probeConnector }

func (p *probeConn) Ping(context.Context) error {
	p.connector.pings.Add(1)
	p.connector.called <- struct{}{}
	return nil
}
func (*probeConn) Prepare(string) (driver.Stmt, error) { return nil, errors.New("unused") }
func (*probeConn) Close() error                        { return nil }
func (*probeConn) Begin() (driver.Tx, error)           { return nil, errors.New("unused") }
