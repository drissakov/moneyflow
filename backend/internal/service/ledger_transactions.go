package service

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"gorm.io/gorm"
)

type HistoryFilter struct {
	AccountID      string `json:"account_id"`
	Cursor         string `json:"cursor"`
	Query          string `json:"q"`
	From           string `json:"from"`
	To             string `json:"to"`
	CategoryID     string `json:"category_id"`
	Kind           string `json:"kind"`
	IncludeDeleted bool   `json:"include_deleted"`
	Limit          int    `json:"limit"`
	Currency       string `json:"currency,omitempty"`
	AnalyticsOnly  bool   `json:"analytics_only,omitempty"`
	Uncategorized  bool   `json:"uncategorized,omitempty"`
	CategoryExact  bool   `json:"category_exact,omitempty"`
}
type historyCursor struct {
	OccurredAt time.Time `json:"o"`
	CreatedAt  time.Time `json:"c"`
	ID         string    `json:"i"`
	Filter     string    `json:"f"`
}
type HistoryView struct {
	Transactions []TransactionView `json:"transactions"`
	NextCursor   *string           `json:"next_cursor"`
}

func validateHistoryFilter(f HistoryFilter) (HistoryFilter, error) {
	var err error
	if f.Currency != "" {
		if err := ValidateCurrency(f.Currency); err != nil {
			return f, err
		}
	}
	if f.AnalyticsOnly && f.IncludeDeleted {
		return f, Invalid("Analytics_only cannot include deleted transactions.")
	}
	if f.Uncategorized && (f.CategoryID != "" || f.CategoryExact) {
		return f, Invalid("Uncategorized cannot be combined with a category filter.")
	}
	if f.CategoryExact && f.CategoryID == "" {
		return f, Invalid("Category_exact requires category_id.")
	}
	if f.AccountID != "" {
		f.AccountID, err = ParseID(f.AccountID)
		if err != nil {
			return f, err
		}
	}
	if f.CategoryID != "" {
		f.CategoryID, err = ParseID(f.CategoryID)
		if err != nil {
			return f, err
		}
	}
	if f.Kind != "" && f.Kind != "expense" && f.Kind != "income" {
		return f, Invalid("Kind must be income or expense.")
	}
	f.Query = strings.TrimSpace(f.Query)
	if !utf8.ValidString(f.Query) || utf8.RuneCountInString(f.Query) > 100 {
		return f, Invalid("Search must have at most 100 characters.")
	}
	if f.Limit < 1 || f.Limit > 100 {
		return f, Invalid("Limit must be between 1 and 100.")
	}
	for _, value := range []string{f.From, f.To} {
		if value != "" {
			if parsed, err := time.Parse(time.RFC3339Nano, value); err != nil || parsed.UTC().Year() < 1 || parsed.UTC().Year() > 9999 {
				return f, Invalid("From and to must be RFC3339 timestamps.")
			}
		}
	}
	if f.From != "" && f.To != "" {
		from, _ := time.Parse(time.RFC3339Nano, f.From)
		to, _ := time.Parse(time.RFC3339Nano, f.To)
		if !from.Before(to) {
			return f, Invalid("From must precede to.")
		}
	}
	return f, nil
}

func (s *Service) History(ctx context.Context, userID string, f HistoryFilter) (HistoryView, error) {
	view := HistoryView{Transactions: make([]TransactionView, 0)}
	f, err := validateHistoryFilter(f)
	if err != nil {
		return view, err
	}
	db := s.DB.WithContext(ctx)
	if f.AccountID != "" {
		var account model.Account
		if err := db.Select("id,currency").Where("id=? AND user_id=?", f.AccountID, userID).First(&account).Error; errors.Is(err, gorm.ErrRecordNotFound) {
			return view, NotFound
		} else if err != nil {
			return view, err
		}
		if f.Currency != "" && f.Currency != account.Currency {
			return view, Invalid("Selected account currency must match the report currency.")
		}
	}
	if f.CategoryID != "" {
		if _, err := findVisibleCategory(db, userID, f.CategoryID); err != nil {
			return view, err
		}
	}
	query := db.Model(&model.Transaction{}).Select("transactions.*").Where("transactions.user_id=?", userID)
	if !f.IncludeDeleted {
		query = query.Where("transactions.deleted_at IS NULL")
	}
	if f.AccountID != "" {
		query = query.Where("transactions.account_id=?", f.AccountID)
	}
	if f.Kind != "" {
		query = query.Where("transactions.kind=?", f.Kind)
	}
	if f.Currency != "" {
		query = query.Where("transactions.currency=?", f.Currency)
	}
	if f.AnalyticsOnly {
		query = query.Where("NOT transactions.exclude_from_analytics")
	}
	if f.Uncategorized {
		query = query.Where("transactions.category_id IS NULL")
	}
	if f.CategoryID != "" {
		if f.CategoryExact {
			query = query.Where("transactions.category_id=?", f.CategoryID)
		} else {
			query = query.Where("transactions.category_id IN (SELECT id FROM categories WHERE id=? OR parent_id=?)", f.CategoryID, f.CategoryID)
		}
	}
	if f.From != "" {
		query = query.Where("transactions.occurred_at>=?", f.From)
	}
	if f.To != "" {
		query = query.Where("transactions.occurred_at<?", f.To)
	}
	if f.Query != "" {
		query = query.Joins("JOIN accounts ha ON ha.id=transactions.account_id").Joins("LEFT JOIN categories hc ON hc.id=transactions.category_id").Joins("LEFT JOIN category_overrides ho ON ho.category_id=hc.id AND ho.user_id=transactions.user_id")
		// strpos deliberately treats SQL wildcard characters as literal search text.
		query = query.Where("strpos(translate(lower(transactions.note || ' ' || ha.name || ' ' || COALESCE(ho.name,hc.name,'') || ' ' || transactions.amount_minor::text),'АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯӘҒҚҢӨҰҮҺІ','абвгдеёжзийклмнопрстуфхцчшщъыьэюяәғқңөұүһі'),?)>0", strings.ToLower(f.Query))
	}
	signatureFilter := f
	signatureFilter.Cursor = ""
	signatureFilter.Limit = 0
	signature, _ := json.Marshal(signatureFilter)
	if f.Cursor != "" {
		if len(f.Cursor) > 2048 {
			return view, Invalid("Invalid history cursor.")
		}
		raw, e := base64.RawURLEncoding.DecodeString(f.Cursor)
		var cursor historyCursor
		if e != nil || json.Unmarshal(raw, &cursor) != nil || cursor.Filter != string(signature) || cursor.ID == "" || cursor.OccurredAt.IsZero() || cursor.CreatedAt.IsZero() {
			return view, Invalid("Invalid history cursor for these filters.")
		}
		if _, e = ParseID(cursor.ID); e != nil {
			return view, Invalid("Invalid history cursor.")
		}
		query = query.Where("(transactions.occurred_at,transactions.created_at,transactions.id)<(?,?,?::uuid)", cursor.OccurredAt, cursor.CreatedAt, cursor.ID)
	}
	var records []model.Transaction
	if err := query.Order("transactions.occurred_at DESC,transactions.created_at DESC,transactions.id DESC").Limit(f.Limit + 1).Find(&records).Error; err != nil {
		return view, err
	}
	if len(records) > f.Limit {
		records = records[:f.Limit]
		last := records[len(records)-1]
		raw, _ := json.Marshal(historyCursor{last.OccurredAt, last.CreatedAt, last.ID, string(signature)})
		next := base64.RawURLEncoding.EncodeToString(raw)
		view.NextCursor = &next
	}
	for _, t := range records {
		view.Transactions = append(view.Transactions, transactionView(t))
	}
	return view, populateTransactionSources(db, view.Transactions)
}

type UpdateTransactionInput struct {
	ExpectedVersion      int64   `json:"expected_version"`
	AccountID            string  `json:"account_id"`
	Kind                 string  `json:"kind"`
	AmountMinor          string  `json:"amount_minor"`
	Note                 string  `json:"note"`
	OccurredAt           string  `json:"occurred_at"`
	CategoryID           *string `json:"category_id"`
	ExcludeFromAnalytics bool    `json:"exclude_from_analytics"`
}
type ExpectedVersion struct {
	ExpectedVersion int64 `json:"expected_version"`
}

func (s *Service) UpdateTransaction(ctx context.Context, userID, id, key string, input UpdateTransactionInput) ([]byte, error) {
	v, err := ValidateTransaction(input.AccountID, input.Kind, input.AmountMinor, input.Note, input.OccurredAt)
	if err != nil {
		return nil, err
	}
	v.CategoryID, err = ParseOptionalID(input.CategoryID)
	if err != nil {
		return nil, err
	}
	return s.mutate(ctx, userID, key, "PUT /api/v1/transactions/"+id, input, func(tx *gorm.DB) (any, error) {
		t, err := lockedTransaction(tx, userID, id)
		if err != nil {
			return nil, err
		}
		if err := requireIndependentTransaction(t); err != nil {
			return nil, err
		}
		if err := checkVersion(t.Version, input.ExpectedVersion); err != nil {
			return nil, err
		}
		if t.DeletedAt != nil {
			return nil, &Error{409, "transaction_deleted", "Restore this transaction before editing it."}
		}
		var source model.TransactionImportSource
		err = tx.Where("transaction_id=?", t.ID).First(&source).Error
		if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, err
		}
		if source.TransactionID != "" && v.AccountID != t.AccountID {
			return nil, &Error{409, "imported_account_immutable", "Imported transactions must remain in their original account. Undo and reimport to another account instead."}
		}
		accounts, err := lockAccounts(tx, userID, []string{t.AccountID, v.AccountID}, true)
		if err != nil {
			return nil, err
		}
		if v.Kind != t.Kind || categorySortID(v.CategoryID) != categorySortID(t.CategoryID) {
			if err := validateCategoryAssignment(tx, userID, v.Kind, v.CategoryID); err != nil {
				return nil, err
			}
		}
		t.AccountID, t.Kind, t.AmountMinor, t.Currency, t.Note, t.OccurredAt, t.CategoryID, t.ExcludeFromAnalytics = v.AccountID, v.Kind, v.Amount, accounts[v.AccountID].Currency, v.Note, v.OccurredAt, v.CategoryID, input.ExcludeFromAnalytics
		t.Version++
		if err := tx.Save(&t).Error; err != nil {
			return nil, err
		}
		if err := ensureAccountBalances(tx, accounts); err != nil {
			return nil, err
		}
		return enrichedTransactionView(tx, t)
	})
}

func (s *Service) ChangeTransactionState(ctx context.Context, userID, id, key string, input ExpectedVersion, restore bool) ([]byte, error) {
	path := "DELETE /api/v1/transactions/" + id
	if restore {
		path = "RESTORE /api/v1/transactions/" + id
	}
	return s.mutate(ctx, userID, key, path, input, func(tx *gorm.DB) (any, error) {
		t, err := lockedTransaction(tx, userID, id)
		if err != nil {
			return nil, err
		}
		if err := requireIndependentTransaction(t); err != nil {
			return nil, err
		}
		if err := checkVersion(t.Version, input.ExpectedVersion); err != nil {
			return nil, err
		}
		if restore && t.DeletedAt == nil {
			return nil, &Error{409, "transaction_active", "Transaction is already active."}
		}
		if !restore && t.DeletedAt != nil {
			return nil, &Error{409, "transaction_deleted", "Transaction is already deleted."}
		}
		accounts, err := lockAccounts(tx, userID, []string{t.AccountID}, restore)
		if err != nil {
			return nil, err
		}
		if restore {
			// Restoration preserves the original archived category because it is history,
			// not a new assignment. Import undo hard-deletes rows and cannot be reversed here.
			t.DeletedAt = nil
		} else {
			now := time.Now().UTC().Truncate(time.Microsecond)
			t.DeletedAt = &now
		}
		t.Version++
		if err := tx.Save(&t).Error; err != nil {
			return nil, err
		}
		if err := ensureAccountBalances(tx, accounts); err != nil {
			return nil, err
		}
		return enrichedTransactionView(tx, t)
	})
}
