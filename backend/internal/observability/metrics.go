// Package observability exposes operational metrics on a separate internal listener.
package observability

import (
	"context"
	"database/sql"
	"net/http"
	"strconv"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/collectors"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type Metrics struct {
	registry *prometheus.Registry
	requests *prometheus.CounterVec
	duration *prometheus.HistogramVec
	db       *sql.DB
	ready    atomic.Int64
	checked  atomic.Int64
}

func New(db *sql.DB) *Metrics {
	m := &Metrics{
		registry: prometheus.NewRegistry(), db: db,
		requests: prometheus.NewCounterVec(prometheus.CounterOpts{
			Name: "moneyflow_http_requests_total", Help: "Completed API requests by bounded method, route template and HTTP status.",
		}, []string{"method", "route", "status"}),
		duration: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Name: "moneyflow_http_request_duration_seconds", Help: "API request latency in seconds, including middleware and authentication.",
			Buckets: []float64{0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 15},
		}, []string{"method", "route"}),
	}
	m.registry.MustRegister(m.requests, m.duration, collectors.NewGoCollector(), collectors.NewProcessCollector(collectors.ProcessCollectorOpts{}),
		newPoolCollector(db),
		prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: "moneyflow_database_ready", Help: "Whether the last background database probe succeeded."}, func() float64 { return float64(m.ready.Load()) }),
		prometheus.NewGaugeFunc(prometheus.GaugeOpts{Name: "moneyflow_database_probe_timestamp_seconds", Help: "Unix timestamp of the last completed background database probe."}, func() float64 { return float64(m.checked.Load()) }),
	)
	return m
}

// Middleware never exports request URLs, IDs, query strings, IPs or credentials.
// It wraps recovery and authentication so early rejections and panics are counted.
func (m *Metrics) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		route := c.FullPath()
		if route == "" {
			route = "unmatched"
		}
		method := boundedMethod(c.Request.Method)
		status := c.Writer.Status()
		if status < 100 || status > 599 {
			status = 500
		}
		m.requests.WithLabelValues(method, route, strconv.Itoa(status)).Inc()
		m.duration.WithLabelValues(method, route).Observe(time.Since(start).Seconds())
	}
}

func boundedMethod(method string) string {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodPatch, http.MethodOptions, http.MethodConnect, http.MethodTrace:
		return method
	default:
		return "OTHER"
	}
}

// Handler serves only GET /metrics. Mount it on the metrics listener, never the API.
func (m *Metrics) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /metrics", promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{
		Timeout: 5 * time.Second, MaxRequestsInFlight: 2,
	}))
	return mux
}

// ProbeDatabase runs at most one bounded probe at a time. Scraping metrics only
// reads cached readiness and database pool statistics, without SQL queries.
func (m *Metrics) ProbeDatabase(ctx context.Context) {
	probe := func() {
		ready := int64(0)
		if m.db != nil {
			probeCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
			if m.db.PingContext(probeCtx) == nil {
				ready = 1
			}
			cancel()
		}
		m.ready.Store(ready)
		m.checked.Store(time.Now().Unix())
	}
	probe()
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			probe()
		}
	}
}

type poolCollector struct {
	db    *sql.DB
	descs []*prometheus.Desc
}

func newPoolCollector(db *sql.DB) *poolCollector {
	return &poolCollector{db: db, descs: []*prometheus.Desc{
		prometheus.NewDesc("moneyflow_database_connections_open", "Open database connections.", nil, nil),
		prometheus.NewDesc("moneyflow_database_connections_in_use", "Database connections currently in use.", nil, nil),
		prometheus.NewDesc("moneyflow_database_connections_idle", "Idle database connections.", nil, nil),
		prometheus.NewDesc("moneyflow_database_connections_max", "Configured maximum open database connections (zero means unlimited).", nil, nil),
		prometheus.NewDesc("moneyflow_database_connection_waits_total", "Number of waits for a database pool connection.", nil, nil),
		prometheus.NewDesc("moneyflow_database_connection_wait_duration_seconds_total", "Total time spent waiting for database pool connections.", nil, nil),
	}}
}

func (p *poolCollector) Describe(ch chan<- *prometheus.Desc) {
	for _, desc := range p.descs {
		ch <- desc
	}
}

func (p *poolCollector) Collect(ch chan<- prometheus.Metric) {
	var stats sql.DBStats
	if p.db != nil {
		stats = p.db.Stats()
	}
	values := []float64{float64(stats.OpenConnections), float64(stats.InUse), float64(stats.Idle), float64(stats.MaxOpenConnections), float64(stats.WaitCount), stats.WaitDuration.Seconds()}
	for i, value := range values {
		kind := prometheus.GaugeValue
		if i >= 4 {
			kind = prometheus.CounterValue
		}
		ch <- prometheus.MustNewConstMetric(p.descs[i], kind, value)
	}
}
