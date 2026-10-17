package model

import "time"

type Budget struct {
	ID             string `gorm:"type:uuid;primaryKey"`
	UserID         string `gorm:"type:uuid"`
	Name           string
	CategoryID     string `gorm:"type:uuid"`
	Currency       string
	LimitMinor     int64
	PeriodStartDay int
	Archived       bool
	Version        int64
	CreatedAt      time.Time
}

type Schedule struct {
	ID            string `gorm:"type:uuid;primaryKey"`
	UserID        string `gorm:"type:uuid"`
	Name          string
	AccountID     string  `gorm:"type:uuid"`
	CategoryID    *string `gorm:"type:uuid"`
	Kind          string
	Currency      string
	AmountMinor   int64
	Note          string
	StartDate     time.Time  `gorm:"type:date"`
	EndDate       *time.Time `gorm:"type:date"`
	Frequency     string
	Interval      int
	Mandatory     bool
	PrimaryIncome bool
	Paused        bool
	Archived      bool
	Version       int64
	CreatedAt     time.Time
}

type PlannedOccurrence struct {
	ScheduleID      string    `gorm:"type:uuid;primaryKey"`
	UserID          string    `gorm:"type:uuid"`
	DueDate         time.Time `gorm:"type:date;primaryKey"`
	Version         int64
	TemplateVersion int64
	Status          string
	Snapshot        string  `gorm:"type:jsonb"`
	TransactionID   *string `gorm:"type:uuid"`
	CreatedAt       time.Time
}

type PlanningSetting struct {
	UserID          string `gorm:"type:uuid;primaryKey"`
	ReserveMinor    int64
	ReserveCurrency string
	AccountIDs      string `gorm:"type:jsonb"`
	Version         int64
}
