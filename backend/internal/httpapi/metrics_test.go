package httpapi

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/drissakov/moneyflow/backend/internal/observability"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
)

func TestInternalMetricsAndAuthenticationFailures(t *testing.T) {
	gin.SetMode(gin.TestMode)
	metrics := observability.New(nil)
	router, err := New(&service.Service{}, Options{Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Metrics: metrics})
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/metrics", "/metrics?token=private-query", "/private-user-path"} {
		response := httptest.NewRecorder()
		router.ServeHTTP(response, httptest.NewRequest(http.MethodGet, path, nil))
		if response.Code != http.StatusNotFound {
			t.Fatalf("public endpoint %s status=%d", path, response.Code)
		}
	}
	request := httptest.NewRequest(http.MethodPut, "/api/v1/transactions/private-account-id/category?email=private-email", nil)
	request.Header.Set("Authorization", "Bearer")
	response := httptest.NewRecorder()
	router.ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("unauthorized request status=%d", response.Code)
	}
	scrape := httptest.NewRecorder()
	metrics.Handler().ServeHTTP(scrape, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if scrape.Code != http.StatusOK {
		t.Fatalf("internal metrics status=%d", scrape.Code)
	}
	body := scrape.Body.String()
	for _, secret := range []string{"private-query", "private-user-path", "private-account-id", "private-email", "Bearer"} {
		if strings.Contains(body, secret) {
			t.Fatalf("metrics exposed private request data: %s", secret)
		}
	}
	if !strings.Contains(body, `moneyflow_http_requests_total{method="PUT",route="/api/v1/transactions/:id/category",status="401"} 1`) ||
		!strings.Contains(body, `moneyflow_http_requests_total{method="GET",route="unmatched",status="404"} 3`) {
		t.Fatal("middleware did not count rejected requests with route templates")
	}
}
