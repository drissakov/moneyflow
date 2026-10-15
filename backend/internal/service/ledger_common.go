package service

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"math"
	"sort"
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

// Every balance/category writer takes this user-scoped lock before any entity
// locks, including legacy imports. This establishes one lock order across APIs.
func lockLedger(tx *gorm.DB, userID string) error {
	h := sha256.Sum256([]byte("ledger:" + userID))
	return tx.Exec("SELECT pg_advisory_xact_lock(?)", int64(binary.BigEndian.Uint64(h[:8]))).Error
}

func (s *Service) mutate(ctx context.Context, userID, key, path string, payload any, fn func(*gorm.DB) (any, error)) ([]byte, error) {
	if _, err := ParseID(key); err != nil {
		return nil, err
	}
	canonical, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	hash := sha256.Sum256(append([]byte(path+"\n"), canonical...))
	var response []byte
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockLedger(tx, userID); err != nil {
			return err
		}
		if err := lockImportKey(tx, userID, key); err != nil {
			return err
		}
		body, exists, err := readImportReplay(tx, userID, key, hash[:])
		if err != nil {
			return err
		}
		if exists {
			response = body
			return nil
		}
		result, err := fn(tx)
		if err != nil {
			return err
		}
		response, err = json.Marshal(result)
		if err != nil {
			return err
		}
		return tx.Create(&model.IdempotencyRecord{UserID: userID, Key: key, RequestHash: hash[:], ResponseBody: response, CreatedAt: time.Now().UTC()}).Error
	})
	return response, err
}

func checkVersion(actual, expected int64) error {
	if expected < 1 {
		return Invalid("Expected_version must be a positive integer.")
	}
	if actual == math.MaxInt64 {
		return &Error{409, "version_conflict", "Item version limit reached."}
	}
	if actual != expected {
		return &Error{409, "version_conflict", "This item changed. Refresh it before trying again."}
	}
	return nil
}

func lockAccounts(tx *gorm.DB, userID string, ids []string, active bool) (map[string]model.Account, error) {
	sort.Strings(ids)
	result := make(map[string]model.Account)
	for _, id := range ids {
		if _, ok := result[id]; ok {
			continue
		}
		var a model.Account
		err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ? AND user_id = ?", id, userID).First(&a).Error
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, NotFound
		}
		if err != nil {
			return nil, err
		}
		if active && a.Archived {
			return nil, &Error{409, "archived_account", "Unarchive the account before adding or editing operations."}
		}
		result[id] = a
	}
	return result, nil
}

func ensureAccountBalances(tx *gorm.DB, accounts map[string]model.Account) error {
	for _, a := range accounts {
		if _, err := importBalance(tx, a); err != nil {
			return err
		}
	}
	return nil
}

func populateTransactionSources(tx *gorm.DB, views []TransactionView) error {
	ids := make([]string, 0, len(views))
	for _, v := range views {
		ids = append(ids, v.ID)
	}
	if len(ids) == 0 {
		return nil
	}
	var sources []model.TransactionImportSource
	if err := tx.Where("transaction_id IN ?", ids).Find(&sources).Error; err != nil {
		return err
	}
	byID := map[string]string{}
	for _, src := range sources {
		byID[src.TransactionID] = src.ImportID
	}
	for i := range views {
		views[i].Source = "manual"
		if views[i].TransferID != nil {
			views[i].Source = "transfer"
		} else if id, ok := byID[views[i].ID]; ok {
			views[i].Source = "import"
			views[i].ImportID = &id
		}
	}
	return nil
}

func enrichedTransactionView(tx *gorm.DB, t model.Transaction) (TransactionView, error) {
	views := []TransactionView{transactionView(t)}
	err := populateTransactionSources(tx, views)
	return views[0], err
}

// IDs are fetched only after owner filtering and locked within the user ledger.
func lockedTransaction(tx *gorm.DB, userID, id string) (model.Transaction, error) {
	var t model.Transaction
	err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).Where("id = ? AND user_id = ?", id, userID).First(&t).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		return t, &Error{404, "transaction_not_found", "Transaction was not found."}
	}
	return t, err
}

func requireIndependentTransaction(t model.Transaction) error {
	if t.TransferID != nil {
		return &Error{409, "transfer_managed", "Edit, delete or restore the entire transfer instead of one of its legs."}
	}
	return nil
}

func currentAccountView(tx *gorm.DB, userID, id string) (AccountView, error) {
	var a model.Account
	if err := tx.Where("id = ? AND user_id = ?", id, userID).First(&a).Error; err != nil {
		return AccountView{}, err
	}
	b, err := importBalance(tx, a)
	if err != nil {
		return AccountView{}, err
	}
	return accountView(a, strconv.FormatInt(b, 10)), nil
}
