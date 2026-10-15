package model

import "time"

type User struct {
	ID           string `gorm:"type:uuid;primaryKey"`
	Email        string
	PasswordHash string
	BaseCurrency string `gorm:"default:KZT"`
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
	Type                string `gorm:"default:card"`
	Archived            bool
	IncludeInBalance    bool `gorm:"default:true"`
	SortOrder           int
	Version             int64 `gorm:"default:1"`
	CreatedAt           time.Time
}

type Transaction struct {
	ID                   string `gorm:"type:uuid;primaryKey"`
	UserID               string `gorm:"type:uuid"`
	AccountID            string `gorm:"type:uuid"`
	Kind                 string
	AmountMinor          int64
	Currency             string
	Note                 string
	OccurredAt           time.Time
	CreatedAt            time.Time
	CategoryID           *string `gorm:"type:uuid"`
	ExcludeFromAnalytics bool
	Version              int64 `gorm:"default:1"`
	DeletedAt            *time.Time
	TransferID           *string `gorm:"type:uuid"`
	TransferRole         *string
}

type Category struct {
	ID        string  `gorm:"type:uuid;primaryKey"`
	UserID    *string `gorm:"type:uuid"`
	Kind      string
	Name      string
	ParentID  *string `gorm:"type:uuid"`
	IsDefault bool
	Archived  bool
	SortOrder int
	Version   int64 `gorm:"default:1"`
	CreatedAt time.Time
}

type IdempotencyRecord struct {
	UserID       string `gorm:"type:uuid;primaryKey"`
	Key          string `gorm:"type:uuid;primaryKey"`
	RequestHash  []byte
	ResponseBody []byte
	CreatedAt    time.Time
}

type CategoryOverride struct {
	UserID     string `gorm:"type:uuid;primaryKey"`
	CategoryID string `gorm:"type:uuid;primaryKey"`
	Name       string
	Archived   bool
	SortOrder  int
	Version    int64 `gorm:"default:1"`
}

type Transfer struct {
	ID              string `gorm:"type:uuid;primaryKey"`
	UserID          string `gorm:"type:uuid"`
	FromAccountID   string `gorm:"type:uuid"`
	ToAccountID     string `gorm:"type:uuid"`
	FromAmountMinor int64
	ToAmountMinor   int64
	FeeAccountID    *string `gorm:"type:uuid"`
	FeeAmountMinor  int64
	Note            string
	OccurredAt      time.Time
	CreatedAt       time.Time
	Version         int64 `gorm:"default:1"`
	DeletedAt       *time.Time
}
