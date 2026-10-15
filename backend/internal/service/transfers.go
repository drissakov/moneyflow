package service

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type TransferInput struct {
	ExpectedVersion int64   `json:"expected_version,omitempty"`
	FromAccountID   string  `json:"from_account_id"`
	ToAccountID     string  `json:"to_account_id"`
	FromAmountMinor string  `json:"from_amount_minor"`
	ToAmountMinor   string  `json:"to_amount_minor"`
	FeeAccountID    *string `json:"fee_account_id"`
	FeeAmountMinor  string  `json:"fee_amount_minor"`
	Note            string  `json:"note"`
	OccurredAt      string  `json:"occurred_at"`
}

type TransferView struct {
	ID              string            `json:"id"`
	FromAccountID   string            `json:"from_account_id"`
	ToAccountID     string            `json:"to_account_id"`
	FromAmountMinor string            `json:"from_amount_minor"`
	ToAmountMinor   string            `json:"to_amount_minor"`
	FromCurrency    string            `json:"from_currency"`
	ToCurrency      string            `json:"to_currency"`
	FeeAccountID    *string           `json:"fee_account_id"`
	FeeAmountMinor  string            `json:"fee_amount_minor"`
	FeeCurrency     *string           `json:"fee_currency"`
	Note            string            `json:"note"`
	OccurredAt      time.Time         `json:"occurred_at"`
	CreatedAt       time.Time         `json:"created_at"`
	Version         int64             `json:"version"`
	DeletedAt       *time.Time        `json:"deleted_at"`
	Transactions    []TransactionView `json:"transactions"`
}

type transferValues struct {
	From, To, Fee int64
	OccurredAt    time.Time
	Accounts      []string
}

func validateTransfer(input TransferInput) (TransferInput, transferValues, error) {
	var result transferValues
	from, err := ValidateTransaction(input.FromAccountID, "expense", input.FromAmountMinor, input.Note, input.OccurredAt)
	if err != nil {
		return input, result, err
	}
	to, err := ValidateTransaction(input.ToAccountID, "income", input.ToAmountMinor, input.Note, input.OccurredAt)
	if err != nil {
		return input, result, err
	}
	if from.AccountID == to.AccountID {
		return input, result, Invalid("Transfer source and destination must differ.")
	}
	if input.FeeAmountMinor == "" {
		input.FeeAmountMinor = "0"
	}
	fee, err := ParseMinor(input.FeeAmountMinor, false)
	if err != nil {
		return input, result, err
	}
	if fee < 0 {
		return input, result, Invalid("Transfer fee must not be negative.")
	}
	feeID, err := ParseOptionalID(input.FeeAccountID)
	if err != nil {
		return input, result, err
	}
	if (fee > 0) != (feeID != nil) {
		return input, result, Invalid("A positive fee requires a fee account; zero fee must have no fee account.")
	}
	input.FromAccountID, input.ToAccountID, input.FeeAccountID = from.AccountID, to.AccountID, feeID
	result = transferValues{From: from.Amount, To: to.Amount, Fee: fee, OccurredAt: from.OccurredAt, Accounts: []string{from.AccountID, to.AccountID}}
	if feeID != nil {
		result.Accounts = append(result.Accounts, *feeID)
	}
	return input, result, nil
}

func lockedTransfer(tx *gorm.DB, userID, id string) (model.Transfer, error) {
	var transfer model.Transfer
	err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id=? AND user_id=?", id, userID).First(&transfer).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return transfer, &Error{404, "transfer_not_found", "Transfer was not found."}
	}
	return transfer, err
}

func viewTransfer(tx *gorm.DB, t model.Transfer) (TransferView, error) {
	view := TransferView{ID: t.ID, FromAccountID: t.FromAccountID, ToAccountID: t.ToAccountID, FromAmountMinor: strconv.FormatInt(t.FromAmountMinor, 10), ToAmountMinor: strconv.FormatInt(t.ToAmountMinor, 10), FeeAccountID: t.FeeAccountID, FeeAmountMinor: strconv.FormatInt(t.FeeAmountMinor, 10), Note: t.Note, OccurredAt: t.OccurredAt, CreatedAt: t.CreatedAt, Version: t.Version, DeletedAt: t.DeletedAt, Transactions: make([]TransactionView, 0)}
	var legs []model.Transaction
	if err := tx.Where("transfer_id=? AND user_id=?", t.ID, t.UserID).Order("CASE transfer_role WHEN 'from' THEN 0 WHEN 'to' THEN 1 ELSE 2 END").Find(&legs).Error; err != nil {
		return view, err
	}
	for _, leg := range legs {
		v := transactionView(leg)
		v.Source = "transfer"
		view.Transactions = append(view.Transactions, v)
		switch *leg.TransferRole {
		case "from":
			view.FromCurrency = leg.Currency
		case "to":
			view.ToCurrency = leg.Currency
		case "fee":
			currency := leg.Currency
			view.FeeCurrency = &currency
		}
	}
	return view, nil
}

func transferAccountIDs(t model.Transfer) []string {
	ids := []string{t.FromAccountID, t.ToAccountID}
	if t.FeeAccountID != nil {
		ids = append(ids, *t.FeeAccountID)
	}
	return ids
}

func writeTransferLegs(tx *gorm.DB, t model.Transfer, accounts map[string]model.Account) error {
	var previous []model.Transaction
	if err := tx.Where("transfer_id=? AND user_id=?", t.ID, t.UserID).Find(&previous).Error; err != nil {
		return err
	}
	byRole := map[string]model.Transaction{}
	for _, leg := range previous {
		byRole[*leg.TransferRole] = leg
	}
	write := func(role, accountID, kind string, amount int64, exclude bool) error {
		leg, exists := byRole[role]
		if !exists {
			leg = model.Transaction{ID: uuid.NewString(), UserID: t.UserID, TransferID: &t.ID, TransferRole: &role, CreatedAt: t.CreatedAt}
		}
		legVersion := t.Version
		if exists && leg.Version >= legVersion {
			legVersion = leg.Version + 1
		}
		leg.AccountID, leg.Kind, leg.AmountMinor, leg.Currency, leg.Note, leg.OccurredAt, leg.Version, leg.DeletedAt, leg.ExcludeFromAnalytics = accountID, kind, amount, accounts[accountID].Currency, t.Note, t.OccurredAt, legVersion, t.DeletedAt, exclude
		if !exists {
			leg.CategoryID = nil
		}
		if role == "fee" && !exists {
			banking := "30000000-0000-4000-8000-000000000037"
			category, err := findVisibleCategory(tx, t.UserID, banking)
			if err != nil {
				return err
			}
			if categoryActive(tx, t.UserID, category) == nil {
				leg.CategoryID = &banking
			}
		}
		return tx.Save(&leg).Error
	}
	if err := write("from", t.FromAccountID, "expense", t.FromAmountMinor, true); err != nil {
		return err
	}
	if err := write("to", t.ToAccountID, "income", t.ToAmountMinor, true); err != nil {
		return err
	}
	if t.FeeAccountID != nil {
		return write("fee", *t.FeeAccountID, "expense", t.FeeAmountMinor, false)
	}
	return tx.Where("transfer_id=? AND transfer_role='fee'", t.ID).Delete(&model.Transaction{}).Error
}

func (s *Service) SaveTransfer(ctx context.Context, userID, id, key string, input TransferInput) ([]byte, error) {
	input, values, err := validateTransfer(input)
	if err != nil {
		return nil, err
	}
	path := "POST /api/v1/transfers"
	if id != "" {
		path = "PUT /api/v1/transfers/" + id
	}
	return s.mutate(ctx, userID, key, path, input, func(tx *gorm.DB) (any, error) {
		now := time.Now().UTC().Truncate(time.Microsecond)
		t := model.Transfer{ID: uuid.NewString(), UserID: userID, CreatedAt: now, Version: 1}
		ids := append([]string{}, values.Accounts...)
		if id != "" {
			var err error
			t, err = lockedTransfer(tx, userID, id)
			if err != nil {
				return nil, err
			}
			if err := checkVersion(t.Version, input.ExpectedVersion); err != nil {
				return nil, err
			}
			if t.DeletedAt != nil {
				return nil, &Error{409, "transfer_deleted", "Restore this transfer before editing it."}
			}
			ids = append(ids, transferAccountIDs(t)...)
			t.Version++
		}
		accounts, err := lockAccounts(tx, userID, ids, true)
		if err != nil {
			return nil, err
		}
		if accounts[input.FromAccountID].Currency == accounts[input.ToAccountID].Currency && values.From != values.To {
			return nil, Invalid("Same currency transfer amounts must be equal; record any fee separately.")
		}
		t.FromAccountID, t.ToAccountID, t.FromAmountMinor, t.ToAmountMinor, t.FeeAccountID, t.FeeAmountMinor, t.Note, t.OccurredAt = input.FromAccountID, input.ToAccountID, values.From, values.To, input.FeeAccountID, values.Fee, input.Note, values.OccurredAt
		if err := tx.Save(&t).Error; err != nil {
			return nil, err
		}
		if err := writeTransferLegs(tx, t, accounts); err != nil {
			return nil, err
		}
		if err := ensureAccountBalances(tx, accounts); err != nil {
			return nil, err
		}
		return viewTransfer(tx, t)
	})
}

func (s *Service) GetTransfer(ctx context.Context, userID, id string) (TransferView, error) {
	var result TransferView
	err := s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockLedger(tx, userID); err != nil {
			return err
		}
		t, err := lockedTransfer(tx, userID, id)
		if err != nil {
			return err
		}
		result, err = viewTransfer(tx, t)
		return err
	})
	return result, err
}

func (s *Service) ChangeTransferState(ctx context.Context, userID, id, key string, input ExpectedVersion, restore bool) ([]byte, error) {
	path := "DELETE /api/v1/transfers/" + id
	if restore {
		path = "RESTORE /api/v1/transfers/" + id
	}
	return s.mutate(ctx, userID, key, path, input, func(tx *gorm.DB) (any, error) {
		t, err := lockedTransfer(tx, userID, id)
		if err != nil {
			return nil, err
		}
		if err := checkVersion(t.Version, input.ExpectedVersion); err != nil {
			return nil, err
		}
		if restore && t.DeletedAt == nil {
			return nil, &Error{409, "transfer_active", "Transfer is already active."}
		}
		if !restore && t.DeletedAt != nil {
			return nil, &Error{409, "transfer_deleted", "Transfer is already deleted."}
		}
		accounts, err := lockAccounts(tx, userID, transferAccountIDs(t), restore)
		if err != nil {
			return nil, err
		}
		if restore {
			t.DeletedAt = nil
		} else {
			now := time.Now().UTC().Truncate(time.Microsecond)
			t.DeletedAt = &now
		}
		t.Version++
		if err := tx.Save(&t).Error; err != nil {
			return nil, err
		}
		if err := tx.Model(&model.Transaction{}).Where("transfer_id=? AND user_id=?", t.ID, userID).Updates(map[string]any{"deleted_at": t.DeletedAt, "version": gorm.Expr("GREATEST(version+1,?)", t.Version)}).Error; err != nil {
			return nil, err
		}
		if err := ensureAccountBalances(tx, accounts); err != nil {
			return nil, err
		}
		return viewTransfer(tx, t)
	})
}

type TransfersView struct {
	Transfers  []TransferView `json:"transfers"`
	NextCursor *string        `json:"next_cursor"`
}

func (s *Service) Transfers(ctx context.Context, userID, cursor string, limit int) (TransfersView, error) {
	result := TransfersView{Transfers: make([]TransferView, 0)}
	if limit < 1 || limit > 100 {
		return result, Invalid("Limit must be between 1 and 100.")
	}
	err := s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockLedger(tx, userID); err != nil {
			return err
		}
		query := tx.Where("user_id=? AND deleted_at IS NULL", userID)
		if cursor != "" {
			if len(cursor) > 1024 {
				return Invalid("Invalid transfer cursor.")
			}
			raw, e := base64.RawURLEncoding.DecodeString(cursor)
			var c historyCursor
			if e != nil || json.Unmarshal(raw, &c) != nil || c.ID == "" || c.OccurredAt.IsZero() || c.CreatedAt.IsZero() {
				return Invalid("Invalid transfer cursor.")
			}
			if _, e := ParseID(c.ID); e != nil {
				return e
			}
			query = query.Where("(occurred_at,created_at,id)<(?,?,?::uuid)", c.OccurredAt, c.CreatedAt, c.ID)
		}
		var records []model.Transfer
		if err := query.Order("occurred_at DESC,created_at DESC,id DESC").Limit(limit + 1).Find(&records).Error; err != nil {
			return err
		}
		if len(records) > limit {
			records = records[:limit]
			last := records[len(records)-1]
			raw, _ := json.Marshal(historyCursor{OccurredAt: last.OccurredAt, CreatedAt: last.CreatedAt, ID: last.ID})
			value := base64.RawURLEncoding.EncodeToString(raw)
			result.NextCursor = &value
		}
		for _, t := range records {
			view, err := viewTransfer(tx, t)
			if err != nil {
				return err
			}
			result.Transfers = append(result.Transfers, view)
		}
		return nil
	})
	return result, err
}
