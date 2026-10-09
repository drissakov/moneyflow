package main

import (
	"context"
	"log/slog"
	"os"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/database"
	"github.com/drissakov/moneyflow/backend/internal/migrations"
)

func main() {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		slog.Error("DATABASE_URL is required")
		os.Exit(1)
	}
	db, err := database.Open(dsn)
	if err != nil {
		slog.Error("database connection failed")
		os.Exit(1)
	}
	sqlDB, err := db.DB()
	if err != nil {
		slog.Error("database handle unavailable")
		os.Exit(1)
	}
	defer sqlDB.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	if err := migrations.Apply(ctx, sqlDB); err != nil {
		// Avoid printing connection errors that might contain the database password.
		slog.Error("migration failed", "error_type", "migration_error")
		os.Exit(1)
	}
	slog.Info("database migrations applied")
}
