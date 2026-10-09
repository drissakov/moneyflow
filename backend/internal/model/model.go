package model

import "time"

type User struct {
	ID           string `gorm:"type:uuid;primaryKey"`
	Email        string
	PasswordHash string
	CreatedAt    time.Time
}

type Session struct {
	ID        string `gorm:"type:uuid;primaryKey"`
	UserID    string `gorm:"type:uuid"`
	TokenHash []byte
	ExpiresAt time.Time
	CreatedAt time.Time
	RevokedAt *time.Time
}

type Account struct {
	ID                  string `gorm:"type:uuid;primaryKey"`
	UserID              string `gorm:"type:uuid"`
	Name                string
	Currency            string
	OpeningBalanceMinor int64
	CreatedAt           time.Time
}

type Transaction struct {
	ID          string `gorm:"type:uuid;primaryKey"`
	UserID      string `gorm:"type:uuid"`
	AccountID   string `gorm:"type:uuid"`
	Kind        string
	AmountMinor int64
	Currency    string
	Note        string
	OccurredAt  time.Time
	CreatedAt   time.Time
}

type IdempotencyRecord struct {
	UserID       string `gorm:"type:uuid;primaryKey"`
	Key          string `gorm:"type:uuid;primaryKey"`
	RequestHash  []byte
	ResponseBody []byte
	CreatedAt    time.Time
}
