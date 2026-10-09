package service

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"math"
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type AccountView struct {
	ID                  string    `json:"id"`
	Name                string    `json:"name"`
	Currency            string    `json:"currency"`
	OpeningBalanceMinor string    `json:"opening_balance_minor"`
	BalanceMinor        string    `json:"balance_minor"`
	CreatedAt           time.Time `json:"created_at"`
}

type TransactionView struct {
	ID          string    `json:"id"`
	AccountID   string    `json:"account_id"`
	Kind        string    `json:"kind"`
	AmountMinor string    `json:"amount_minor"`
	Currency    string    `json:"currency"`
	Note        string    `json:"note"`
	OccurredAt  time.Time `json:"occurred_at"`
	CreatedAt   time.Time `json:"created_at"`
}

func (s *Service) CreateAccount(ctx context.Context, userID, name, currency, opening string) (AccountView, error) {
	var view AccountView
	name, value, err := ValidateAccount(name, currency, opening)
	if err != nil {
		return view, err
	}
	a := model.Account{
		ID: uuid.NewString(), UserID: userID, Name: name, Currency: currency,
		OpeningBalanceMinor: value, CreatedAt: time.Now().UTC().Truncate(time.Microsecond),
	}
	if err := s.DB.WithContext(ctx).Create(&a).Error; err != nil {
		return view, err
	}
	return AccountView{
		ID: a.ID, Name: a.Name, Currency: a.Currency,
		OpeningBalanceMinor: strconv.FormatInt(value, 10), BalanceMinor: strconv.FormatInt(value, 10), CreatedAt: a.CreatedAt,
	}, nil
}

func (s *Service) Accounts(ctx context.Context, userID string) ([]AccountView, error) {
	accounts := make([]AccountView, 0)
	// SUM(bigint) uses PostgreSQL numeric, so an intermediate sum cannot overflow.
	err := s.DB.WithContext(ctx).Raw(`SELECT a.id, a.name, a.currency, a.created_at,
		a.opening_balance_minor::text AS opening_balance_minor,
		(a.opening_balance_minor::numeric + COALESCE(SUM(CASE
			WHEN t.kind = 'income' THEN t.amount_minor ELSE -t.amount_minor END), 0))::text AS balance_minor
		FROM accounts a LEFT JOIN transactions t ON t.account_id = a.id AND t.user_id = a.user_id
		WHERE a.user_id = ? GROUP BY a.id ORDER BY a.created_at, a.id`, userID).Scan(&accounts).Error
	if err != nil {
		return nil, err
	}
	for _, a := range accounts {
		if _, err := strconv.ParseInt(a.BalanceMinor, 10, 64); err != nil {
			return nil, &Error{409, "balance_overflow", "Account balance exceeds the supported signed 64-bit range."}
		}
	}
	return accounts, nil
}

func transactionView(t model.Transaction) TransactionView {
	return TransactionView{
		ID: t.ID, AccountID: t.AccountID, Kind: t.Kind, AmountMinor: strconv.FormatInt(t.AmountMinor, 10),
		Currency: t.Currency, Note: t.Note, OccurredAt: t.OccurredAt, CreatedAt: t.CreatedAt,
	}
}

func NextBalance(current int64, kind string, amount int64) (int64, error) {
	if kind == "income" {
		if current > math.MaxInt64-amount {
			return 0, &Error{409, "balance_overflow", "This transaction would exceed the supported signed 64-bit balance range."}
		}
		return current + amount, nil
	}
	if current < math.MinInt64+amount {
		return 0, &Error{409, "balance_overflow", "This transaction would exceed the supported signed 64-bit balance range."}
	}
	return current - amount, nil
}

func (s *Service) CreateTransaction(ctx context.Context, userID, key string, input CreateTransactionInput) ([]byte, error) {
	var response []byte
	canonical, err := json.Marshal(input)
	if err != nil {
		return nil, err
	}
	fingerprint := sha256.Sum256(append([]byte("POST /api/v1/transactions\n"), canonical...))
	lockHash := sha256.Sum256([]byte(userID + ":" + key))
	lockID := int64(binary.BigEndian.Uint64(lockHash[:8]))
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// Serialize matching retries before inspecting the durable response. A hash
		// collision only serializes unrelated operations; keys remain exact in storage.
		if err := tx.Exec("SELECT pg_advisory_xact_lock(?)", lockID).Error; err != nil {
			return err
		}
		var existing model.IdempotencyRecord
		err := tx.Where("user_id = ? AND key = ?", userID, key).First(&existing).Error
		if err == nil {
			if !bytes.Equal(existing.RequestHash, fingerprint[:]) {
				return &Error{409, "idempotency_conflict", "Idempotency key was already used with a different request."}
			}
			response = existing.ResponseBody
			return nil
		}
		if !errors.Is(err, gorm.ErrRecordNotFound) {
			return err
		}
		var a model.Account
		// All writers lock the account before calculating the next balance. This
		// prevents two simultaneous, individually valid writes from overflowing it.
		err = tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ? AND user_id = ?", input.AccountID, userID).First(&a).Error
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return NotFound
		}
		if err != nil {
			return err
		}
		var balanceText string
		err = tx.Raw(`SELECT (?::numeric + COALESCE(SUM(CASE
			WHEN kind = 'income' THEN amount_minor ELSE -amount_minor END), 0))::text
			FROM transactions WHERE account_id = ? AND user_id = ?`, a.OpeningBalanceMinor, a.ID, userID).Scan(&balanceText).Error
		if err != nil {
			return err
		}
		balance, err := strconv.ParseInt(balanceText, 10, 64)
		if err != nil {
			return &Error{409, "balance_overflow", "Account balance exceeds the supported signed 64-bit range."}
		}
		if _, err := NextBalance(balance, input.Kind, input.Amount); err != nil {
			return err
		}
		t := model.Transaction{
			ID: uuid.NewString(), UserID: userID, AccountID: a.ID, Kind: input.Kind, AmountMinor: input.Amount,
			Currency: a.Currency, Note: input.Note, OccurredAt: input.OccurredAt,
			CreatedAt: time.Now().UTC().Truncate(time.Microsecond),
		}
		if err := tx.Create(&t).Error; err != nil {
			return err
		}
		response, err = json.Marshal(transactionView(t))
		if err != nil {
			return err
		}
		record := model.IdempotencyRecord{UserID: userID, Key: key, RequestHash: fingerprint[:], ResponseBody: response, CreatedAt: t.CreatedAt}
		return tx.Create(&record).Error
	})
	return response, err
}

func (s *Service) Transactions(ctx context.Context, userID, accountID string, limit int) ([]TransactionView, error) {
	var a model.Account
	err := s.DB.WithContext(ctx).Where("id = ? AND user_id = ?", accountID, userID).First(&a).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, NotFound
	}
	if err != nil {
		return nil, err
	}
	records := make([]model.Transaction, 0)
	err = s.DB.WithContext(ctx).Where("account_id = ? AND user_id = ?", accountID, userID).
		Order("occurred_at DESC, created_at DESC, id DESC").Limit(limit).Find(&records).Error
	if err != nil {
		return nil, err
	}
	views := make([]TransactionView, 0, len(records))
	for _, record := range records {
		views = append(views, transactionView(record))
	}
	return views, nil
}
