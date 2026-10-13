package model

import "time"

type StatementImport struct {
	ID                  string `gorm:"type:uuid;primaryKey"`
	UserID              string `gorm:"type:uuid"`
	AccountID           string `gorm:"type:uuid"`
	Bank                string
	Currency            string
	FileSHA256          string `gorm:"column:file_sha256"`
	Timezone            string
	Status              string
	OpeningBalanceMinor *int64
	ClosingBalanceMinor *int64
	BalanceMinor        int64
	TotalCount          int
	ImportedCount       int
	CreatedAt           time.Time
	ExpiresAt           time.Time
	CommittedAt         *time.Time
	UndoneAt            *time.Time
}

type StatementImportRow struct {
	ImportID             string `gorm:"type:uuid;primaryKey"`
	RowID                int    `gorm:"primaryKey"`
	OccurredAt           time.Time
	LocalDay             string `gorm:"type:date"`
	Kind                 string
	AmountMinor          int64
	Note                 string
	MatchingCount        int64
	CandidateHash        string
	CreatedTransactionID *string `gorm:"type:uuid"`
}

type TransactionImportSource struct {
	TransactionID string `gorm:"type:uuid;primaryKey"`
	ImportID      string `gorm:"type:uuid"`
	UserID        string `gorm:"type:uuid"`
	AccountID     string `gorm:"type:uuid"`
	FileSHA256    string `gorm:"column:file_sha256"`
	RowID         int
}
