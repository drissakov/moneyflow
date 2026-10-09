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
	"github.com/drissakov/moneyflow/backend/internal/httpapi"
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
	db, err := database.Open(cfg.DatabaseURL)
	if err != nil {
		return errors.New("database connection failed")
	}
	sqlDB, err := db.DB()
	if err != nil {
		return errors.New("database handle unavailable")
	}
	defer sqlDB.Close()
	s, err := service.New(db, cfg.SessionTTL)
	if err != nil {
		return errors.New("authentication initialization failed")
	}
	router, err := httpapi.New(s, httpapi.Options{Logger: logger, TrustedProxies: cfg.TrustedProxies})
	if err != nil {
		return errors.New("invalid proxy configuration")
	}
	server := &http.Server{
		Addr: ":" + cfg.Port, Handler: router,
		ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 20 * time.Second,
		WriteTimeout: 20 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 32 * 1024,
	}
	stopped := make(chan error, 1)
	go func() { stopped <- server.ListenAndServe() }()
	logger.Info("server started", "port", cfg.Port)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	select {
	case err := <-stopped:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return errors.New("HTTP listener failed")
	case <-ctx.Done():
		logger.Info("server shutting down")
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			server.Close()
			return errors.New("graceful shutdown timed out")
		}
		return nil
	}
}
