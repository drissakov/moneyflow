package database

import (
	"fmt"
	"time"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

func Open(dsn string) (*gorm.DB, error) {
	return OpenWithPool(dsn, 20, 5, 5*time.Minute)
}

// OpenWithPool bounds each process's database usage. Serverless instances should
// use a small pool and zero idle connections with the provider's pooled URL.
func OpenWithPool(dsn string, maxOpen, maxIdle int, maxIdleTime time.Duration) (*gorm.DB, error) {
	if maxOpen < 1 || maxOpen > 64 || maxIdle < 0 || maxIdle > maxOpen || maxIdleTime < time.Second || maxIdleTime > 30*time.Minute {
		return nil, fmt.Errorf("invalid database pool configuration")
	}
	db, err := gorm.Open(postgres.New(postgres.Config{DSN: dsn, PreferSimpleProtocol: true}), &gorm.Config{
		// The HTTP layer logs operation failures without SQL parameters or credentials.
		Logger:         logger.Default.LogMode(logger.Silent),
		TranslateError: true,
	})
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	sqlDB, err := db.DB()
	if err != nil {
		return nil, err
	}
	sqlDB.SetMaxOpenConns(maxOpen)
	sqlDB.SetMaxIdleConns(maxIdle)
	sqlDB.SetConnMaxIdleTime(maxIdleTime)
	sqlDB.SetConnMaxLifetime(30 * time.Minute)
	return db, nil
}
