package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/config"
	"github.com/drissakov/moneyflow/backend/internal/database"
	"github.com/drissakov/moneyflow/backend/internal/fx"
	"github.com/drissakov/moneyflow/backend/internal/httpapi"
	"github.com/drissakov/moneyflow/backend/internal/observability"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	slog.SetDefault(logger)
	if err := run(logger); err != nil {
		// Never print a database DSN, SQL parameters, passwords, or session tokens.
		logger.Error("server stopped", "reason", err.Error())
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	gin.SetMode(gin.ReleaseMode)
	db, err := database.OpenWithPool(cfg.DatabaseURL, cfg.DatabaseMaxOpen, cfg.DatabaseMaxIdle, cfg.DatabaseMaxIdleTime)
	if err != nil {
		return errors.New("database connection failed")
	}
	sqlDB, err := db.DB()
	if err != nil {
		return errors.New("database handle unavailable")
	}
	defer sqlDB.Close()
	provider, err := fx.NewFrankfurter(cfg.FrankfurterURL)
	if err != nil {
		return errors.New("invalid exchange rate provider configuration")
	}
	s, err := service.New(db, cfg.SessionTTL, service.Options{FXProvider: provider, FXCacheTTL: cfg.FXCacheTTL, FXMaxStale: cfg.FXMaxStale})
	if err != nil {
		return errors.New("authentication initialization failed")
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var metrics *observability.Metrics
	if cfg.MetricsPort != "" {
		metrics = observability.New(sqlDB)
		go metrics.ProbeDatabase(ctx)
	}
	router, err := httpapi.New(s, httpapi.Options{Logger: logger, TrustedProxies: cfg.TrustedProxies, Metrics: metrics})
	if err != nil {
		return errors.New("invalid proxy configuration")
	}
	server := &http.Server{
		Addr: ":" + cfg.Port, Handler: router,
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 20 * time.Second,
		WriteTimeout: 20 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32 * 1024,
	}
	servers := []*http.Server{server}
	if metrics != nil {
		servers = append(servers, &http.Server{
			Addr: ":" + cfg.MetricsPort, Handler: metrics.Handler(),
			ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 10 * time.Second,
			WriteTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 8 * 1024,
		})
	}
	stopped := make(chan error, len(servers))
	for _, listener := range servers {
		go func() { stopped <- listener.ListenAndServe() }()
	}
	logger.Info("server started", "port", cfg.Port, "metrics_port", cfg.MetricsPort)
	var result error
	select {
	case err := <-stopped:
		if !errors.Is(err, http.ErrServerClosed) {
			result = errors.New("HTTP listener failed")
		}
	case <-ctx.Done():
		logger.Info("server shutting down")
	}
	stop()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, listener := range servers {
		if err := listener.Shutdown(shutdownCtx); err != nil {
			listener.Close()
			if result == nil {
				result = errors.New("graceful shutdown timed out")
			}
		}
	}
	return result
}
