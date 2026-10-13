package service

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"math/big"
	"strconv"
	"strings"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/google/uuid"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type ImportPreviewRowView struct {
	RowID         int       `json:"row_id"`
	OccurredAt    time.Time `json:"occurred_at"`
	Kind          string    `json:"kind"`
	AmountMinor   string    `json:"amount_minor"`
	Note          string    `json:"note"`
	Duplicate     string    `json:"duplicate"`
	MatchingCount int64     `json:"matching_count"`
}

type ImportPreviewView struct {
	ID                  string                 `json:"id"`
	AccountID           string                 `json:"account_id"`
	Bank                string                 `json:"bank"`
	Currency            string                 `json:"currency"`
	Status              string                 `json:"status"`
	ExpiresAt           time.Time              `json:"expires_at"`
	BalanceBeforeMinor  string                 `json:"balance_before_minor"`
	OpeningBalanceMinor *string                `json:"opening_balance_minor"`
	ClosingBalanceMinor *string                `json:"closing_balance_minor"`
	Rows                []ImportPreviewRowView `json:"rows"`
}

type ImportReceipt struct {
	ID            string     `json:"id"`
	AccountID     string     `json:"account_id"`
	Bank          string     `json:"bank"`
	Status        string     `json:"status"`
	ImportedCount int        `json:"imported_count"`
	SkippedCount  int        `json:"skipped_count"`
	BalanceMinor  string     `json:"balance_minor"`
	CreatedAt     time.Time  `json:"created_at"`
	CommittedAt   *time.Time `json:"committed_at"`
	UndoneAt      *time.Time `json:"undone_at"`
}

func importReceipt(draft model.StatementImport) ImportReceipt {
	return ImportReceipt{ID: draft.ID, AccountID: draft.AccountID, Bank: draft.Bank, Status: draft.Status,
		ImportedCount: draft.ImportedCount, SkippedCount: draft.TotalCount - draft.ImportedCount,
		BalanceMinor: strconv.FormatInt(draft.BalanceMinor, 10), CreatedAt: draft.CreatedAt, CommittedAt: draft.CommittedAt, UndoneAt: draft.UndoneAt}
}

func optionalBalanceText(value *int64) *string {
	if value == nil {
		return nil
	}
	text := strconv.FormatInt(*value, 10)
	return &text
}

func importAccount(tx *gorm.DB, userID, accountID string) (model.Account, error) {
	var account model.Account
	err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ? AND user_id = ?", accountID, userID).First(&account).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return account, NotFound
	}
	return account, err
}

func importBalance(tx *gorm.DB, account model.Account) (int64, error) {
	var text string
	err := tx.Raw(`SELECT (?::numeric + COALESCE(SUM(CASE WHEN kind='income' THEN amount_minor ELSE -amount_minor END),0))::text
		FROM transactions WHERE account_id = ? AND user_id = ?`, account.OpeningBalanceMinor, account.ID, account.UserID).Scan(&text).Error
	if err != nil {
		return 0, err
	}
	value, err := strconv.ParseInt(text, 10, 64)
	if err != nil {
		return 0, &Error{409, "balance_overflow", "Account balance exceeds the supported signed 64-bit range."}
	}
	return value, nil
}

func (s *Service) PreviewImport(ctx context.Context, userID string, input ImportPreviewInput) (ImportPreviewView, error) {
	var view ImportPreviewView
	draft, rows, _, err := validateImportPreview(input)
	if err != nil {
		return view, err
	}
	now := time.Now().UTC().Truncate(time.Microsecond)
	draft.ID, draft.UserID, draft.Status, draft.CreatedAt, draft.ExpiresAt = uuid.NewString(), userID, "draft", now, now.Add(importDraftTTL)
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		account, err := importAccount(tx, userID, draft.AccountID)
		if err != nil {
			return err
		}
		if account.Currency != draft.Currency {
			return Invalid("Statement currency must match the selected account currency.")
		}
		balance, err := importBalance(tx, account)
		if err != nil {
			return err
		}
		draft.BalanceMinor = balance
		candidates, err := importCandidates(tx, draft, rows)
		if err != nil {
			return err
		}
		exact, err := importExactRows(tx, draft, rows)
		if err != nil {
			return err
		}
		view = ImportPreviewView{ID: draft.ID, AccountID: draft.AccountID, Bank: draft.Bank, Currency: draft.Currency, Status: draft.Status,
			ExpiresAt: draft.ExpiresAt, BalanceBeforeMinor: strconv.FormatInt(balance, 10), OpeningBalanceMinor: optionalBalanceText(draft.OpeningBalanceMinor), ClosingBalanceMinor: optionalBalanceText(draft.ClosingBalanceMinor), Rows: make([]ImportPreviewRowView, 0, len(rows))}
		for i := range rows {
			row := &rows[i]
			row.ImportID = draft.ID
			candidate := candidates[candidateKey(row.LocalDay, row.Kind, row.AmountMinor)]
			row.MatchingCount, row.CandidateHash = candidate.MatchingCount, candidate.CandidateHash
			duplicate := "new"
			if row.MatchingCount > 0 {
				duplicate = "possible"
			}
			if exact[row.RowID] {
				duplicate = "exact"
			}
			view.Rows = append(view.Rows, ImportPreviewRowView{RowID: row.RowID, OccurredAt: row.OccurredAt, Kind: row.Kind, AmountMinor: strconv.FormatInt(row.AmountMinor, 10), Note: row.Note, Duplicate: duplicate, MatchingCount: row.MatchingCount})
		}
		if err := tx.Create(&draft).Error; err != nil {
			return err
		}
		return tx.CreateInBatches(&rows, 200).Error
	})
	return view, err
}

var importNotFound = &Error{404, "import_not_found", "Statement import was not found."}

func lockedImport(tx *gorm.DB, userID, id string) (model.StatementImport, error) {
	var draft model.StatementImport
	err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ? AND user_id = ?", id, userID).First(&draft).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return draft, importNotFound
	}
	return draft, err
}

func lockImportKey(tx *gorm.DB, userID, key string) error {
	hash := sha256.Sum256([]byte(userID + ":" + key))
	return tx.Exec("SELECT pg_advisory_xact_lock(?)", int64(binary.BigEndian.Uint64(hash[:8]))).Error
}

func readImportReplay(tx *gorm.DB, userID, key string, fingerprint []byte) ([]byte, bool, error) {
	var record model.IdempotencyRecord
	err := tx.Where("user_id = ? AND key = ?", userID, key).First(&record).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	if !bytes.Equal(record.RequestHash, fingerprint) {
		return nil, false, &Error{409, "idempotency_conflict", "Idempotency key was already used with a different request."}
	}
	return record.ResponseBody, true, nil
}

func saveImportResponse(tx *gorm.DB, userID, key string, fingerprint []byte, draft model.StatementImport) ([]byte, error) {
	body, err := json.Marshal(importReceipt(draft))
	if err != nil {
		return nil, err
	}
	return body, tx.Create(&model.IdempotencyRecord{UserID: userID, Key: key, RequestHash: fingerprint, ResponseBody: body, CreatedAt: time.Now().UTC().Truncate(time.Microsecond)}).Error
}

func importFingerprint(operation, id string, payload any) ([]byte, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	hash := sha256.Sum256(append([]byte(operation+" /api/v1/imports/"+id+"\n"), body...))
	return hash[:], nil
}

func (s *Service) CommitImport(ctx context.Context, userID, id, key string, input ImportCommitInput) ([]byte, error) {
	var err error
	id, err = ParseID(id)
	if err != nil {
		return nil, err
	}
	key, err = ParseID(key)
	if err != nil {
		return nil, err
	}
	selection, err := validateImportSelection(input)
	if err != nil {
		return nil, err
	}
	fingerprint, err := importFingerprint("COMMIT", id, ImportCommitInput{Rows: selection})
	if err != nil {
		return nil, err
	}
	var response []byte
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockImportKey(tx, userID, key); err != nil {
			return err
		}
		draft, err := lockedImport(tx, userID, id)
		if err != nil {
			return err
		}
		// A historical commit response must never recreate or appear current after undo.
		if draft.Status == "undone" {
			return &Error{409, "import_undone", "This import was undone. Create a fresh preview to import again."}
		}
		if body, exists, err := readImportReplay(tx, userID, key, fingerprint); err != nil {
			return err
		} else if exists {
			response = body
			return nil
		}
		if draft.Status != "draft" {
			return &Error{409, "import_already_committed", "This statement import has already been committed."}
		}
		if !draft.ExpiresAt.After(time.Now()) {
			return &Error{410, "import_expired", "Statement preview expired. Create a fresh preview."}
		}
		account, err := importAccount(tx, userID, draft.AccountID)
		if err != nil {
			return err
		}
		if account.Currency != draft.Currency {
			return Invalid("Statement currency must match the selected account currency.")
		}
		var allRows []model.StatementImportRow
		if err := tx.Select("import_id,row_id,occurred_at,local_day::text AS local_day,kind,amount_minor,note,matching_count,candidate_hash,created_transaction_id").Where("import_id = ?", draft.ID).Find(&allRows).Error; err != nil {
			return err
		}
		byID := make(map[int]model.StatementImportRow, len(allRows))
		for _, row := range allRows {
			byID[row.RowID] = row
		}
		rows := make([]model.StatementImportRow, 0, len(selection))
		validatedCategories := make(map[string]bool)
		for _, selected := range selection {
			row, exists := byID[selected.RowID]
			if !exists {
				return Invalid("Selection contains a row absent from the statement preview.")
			}
			if selected.CategoryID != nil {
				categoryKey := row.Kind + ":" + *selected.CategoryID
				if !validatedCategories[categoryKey] {
					if err := validateCategoryAssignment(tx, userID, row.Kind, selected.CategoryID); err != nil {
						return err
					}
					validatedCategories[categoryKey] = true
				}
			}
			rows = append(rows, row)
		}
		// Recheck all candidates against one pre-insert snapshot, preserving two
		// identical real purchases selected together in this same statement.
		candidates, err := importCandidates(tx, draft, rows)
		if err != nil {
			return err
		}
		exact, err := importExactRows(tx, draft, rows)
		if err != nil {
			return err
		}
		for i, row := range rows {
			if exact[row.RowID] {
				return &Error{409, "import_duplicate_exact", "A selected statement row is already imported. Review and exclude it."}
			}
			candidate := candidates[candidateKey(row.LocalDay, row.Kind, row.AmountMinor)]
			if candidate.MatchingCount > 0 && (candidate.CandidateHash != row.CandidateHash || candidate.MatchingCount != row.MatchingCount) {
				return &Error{409, "import_preview_changed", "Matching transactions changed since preview. Create a fresh preview."}
			}
			if candidate.MatchingCount > 0 && !selection[i].AllowPossibleDuplicate {
				return &Error{409, "import_duplicate_possible", "A selected row may already exist. Explicitly allow it or exclude it."}
			}
		}
		balance, err := importBalance(tx, account)
		if err != nil {
			return err
		}
		total := big.NewInt(balance)
		for _, row := range rows {
			if row.Kind == "income" {
				total.Add(total, big.NewInt(row.AmountMinor))
			} else {
				total.Sub(total, big.NewInt(row.AmountMinor))
			}
		}
		if !total.IsInt64() {
			return &Error{409, "balance_overflow", "This import would exceed the signed 64-bit balance range."}
		}
		now := time.Now().UTC().Truncate(time.Microsecond)
		transactions := make([]model.Transaction, 0, len(rows))
		sources := make([]model.TransactionImportSource, 0, len(rows))
		for i, row := range rows {
			transactionID := uuid.NewString()
			transactions = append(transactions, model.Transaction{ID: transactionID, UserID: userID, AccountID: account.ID, Kind: row.Kind, AmountMinor: row.AmountMinor, Currency: account.Currency, Note: row.Note, OccurredAt: row.OccurredAt, CreatedAt: now, CategoryID: selection[i].CategoryID, ExcludeFromAnalytics: selection[i].ExcludeFromAnalytics})
			sources = append(sources, model.TransactionImportSource{TransactionID: transactionID, ImportID: draft.ID, UserID: userID, AccountID: account.ID, FileSHA256: draft.FileSHA256, RowID: row.RowID})
		}
		if err := tx.CreateInBatches(&transactions, 200).Error; err != nil {
			return err
		}
		if err := tx.CreateInBatches(&sources, 200).Error; err != nil {
			return err
		}
		if err := assignImportTransactionIDs(tx, draft.ID, rows, transactions); err != nil {
			return err
		}
		draft.Status, draft.ImportedCount, draft.BalanceMinor, draft.CommittedAt = "committed", len(rows), total.Int64(), &now
		if err := tx.Model(&draft).Updates(map[string]any{"status": draft.Status, "imported_count": draft.ImportedCount, "balance_minor": draft.BalanceMinor, "committed_at": now}).Error; err != nil {
			return err
		}
		response, err = saveImportResponse(tx, userID, key, fingerprint, draft)
		return err
	})
	return response, err
}

func assignImportTransactionIDs(tx *gorm.DB, importID string, rows []model.StatementImportRow, transactions []model.Transaction) error {
	// Bounded bulk updates avoid one remote-database round trip for every row.
	for start := 0; start < len(rows); start += 200 {
		end := min(start+200, len(rows))
		var values strings.Builder
		args := make([]any, 0, 2*(end-start)+1)
		for i := start; i < end; i++ {
			if i > start {
				values.WriteString(",")
			}
			values.WriteString("(?::integer,?::uuid)")
			args = append(args, rows[i].RowID, transactions[i].ID)
		}
		args = append(args, importID)
		result := tx.Exec("UPDATE statement_import_rows r SET created_transaction_id=v.transaction_id FROM(VALUES "+values.String()+") AS v(row_id,transaction_id) WHERE r.row_id=v.row_id AND r.import_id=?", args...)
		if result.Error != nil {
			return result.Error
		}
		if result.RowsAffected != int64(end-start) {
			return errors.New("import source assignment count mismatch")
		}
	}
	return nil
}

func (s *Service) Imports(ctx context.Context, userID string, limit int) ([]ImportReceipt, error) {
	if limit < 1 || limit > 100 {
		return nil, Invalid("Limit must be between 1 and 100.")
	}
	var drafts []model.StatementImport
	if err := s.DB.WithContext(ctx).Where("user_id = ?", userID).Order("created_at DESC, id DESC").Limit(limit).Find(&drafts).Error; err != nil {
		return nil, err
	}
	views := make([]ImportReceipt, 0, len(drafts))
	for _, draft := range drafts {
		views = append(views, importReceipt(draft))
	}
	return views, nil
}

func (s *Service) UndoImport(ctx context.Context, userID, id, key string) ([]byte, error) {
	var err error
	id, err = ParseID(id)
	if err != nil {
		return nil, err
	}
	key, err = ParseID(key)
	if err != nil {
		return nil, err
	}
	fingerprint, err := importFingerprint("UNDO", id, struct{}{})
	if err != nil {
		return nil, err
	}
	var response []byte
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockImportKey(tx, userID, key); err != nil {
			return err
		}
		draft, err := lockedImport(tx, userID, id)
		if err != nil {
			return err
		}
		if body, exists, err := readImportReplay(tx, userID, key, fingerprint); err != nil {
			return err
		} else if exists {
			response = body
			return nil
		}
		if draft.Status == "draft" {
			return &Error{409, "import_not_committed", "Only a committed statement import can be undone."}
		}
		if draft.Status == "undone" {
			response, err = saveImportResponse(tx, userID, key, fingerprint, draft)
			return err
		}
		account, err := importAccount(tx, userID, draft.AccountID)
		if err != nil {
			return err
		}
		balance, err := importBalance(tx, account)
		if err != nil {
			return err
		}
		var rows []model.StatementImportRow
		if err := tx.Where("import_id = ? AND created_transaction_id IS NOT NULL", draft.ID).Find(&rows).Error; err != nil {
			return err
		}
		ids := make([]string, 0, len(rows))
		for _, row := range rows {
			ids = append(ids, *row.CreatedTransactionID)
		}
		var transactions []model.Transaction
		if len(ids) > 0 {
			if err := tx.Where("id IN ? AND user_id = ? AND account_id = ?", ids, userID, account.ID).Find(&transactions).Error; err != nil {
				return err
			}
		}
		total := big.NewInt(balance)
		for _, t := range transactions {
			if t.Kind == "income" {
				total.Sub(total, big.NewInt(t.AmountMinor))
			} else {
				total.Add(total, big.NewInt(t.AmountMinor))
			}
		}
		if !total.IsInt64() {
			return &Error{409, "balance_overflow", "Undo would exceed the signed 64-bit balance range."}
		}
		if len(ids) > 0 {
			// Cascading source deletion makes these exact PDF rows importable again.
			if err := tx.Where("id IN ? AND user_id = ? AND account_id = ?", ids, userID, account.ID).Delete(&model.Transaction{}).Error; err != nil {
				return err
			}
		}
		now := time.Now().UTC().Truncate(time.Microsecond)
		draft.Status, draft.BalanceMinor, draft.UndoneAt = "undone", total.Int64(), &now
		if err := tx.Model(&draft).Updates(map[string]any{"status": draft.Status, "balance_minor": draft.BalanceMinor, "undone_at": now}).Error; err != nil {
			return err
		}
		response, err = saveImportResponse(tx, userID, key, fingerprint, draft)
		return err
	})
	return response, err
}
