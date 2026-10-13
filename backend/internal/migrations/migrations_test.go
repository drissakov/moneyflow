package migrations

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/database"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

func TestIntegrationMulticurrencyMigrationPreservesExistingLedger(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("set TEST_DATABASE_URL for migration integration test")
	}
	control, err := database.Open(dsn)
	if err != nil {
		t.Fatal("test database connection failed")
	}
	controlSQL, _ := control.DB()
	schema := "moneyflow_migration_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if err := control.Exec("CREATE SCHEMA " + schema).Error; err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { control.Exec("DROP SCHEMA " + schema + " CASCADE"); controlSQL.Close() })
	if strings.HasPrefix(dsn, "postgres://") || strings.HasPrefix(dsn, "postgresql://") {
		u, err := url.Parse(dsn)
		if err != nil {
			t.Fatal("invalid test database URL")
		}
		query := u.Query()
		query.Set("search_path", schema)
		u.RawQuery = query.Encode()
		dsn = u.String()
	} else {
		dsn += " search_path=" + schema
	}
	db, err := database.Open(dsn)
	if err != nil {
		t.Fatal("isolated database connection failed")
	}
	sqlDB, _ := db.DB()
	t.Cleanup(func() { sqlDB.Close() })
	initial, err := files.ReadFile("sql/001_initial.sql")
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256(initial)
	checksum := hex.EncodeToString(hash[:])
	user, account, transaction, session := uuid.NewString(), uuid.NewString(), uuid.NewString(), uuid.NewString()
	// Simulate an actual release-one database, without any002schema or metadata.
	err = db.Transaction(func(tx *gorm.DB) error {
		for _, statement := range []string{string(initial), `CREATE TABLE schema_migrations(version text PRIMARY KEY,checksum text NOT NULL,applied_at timestamptz NOT NULL DEFAULT now())`} {
			if err := tx.Exec(statement).Error; err != nil {
				return err
			}
		}
		if err := tx.Exec("INSERT INTO schema_migrations(version,checksum) VALUES('001_initial.sql',?)", checksum).Error; err != nil {
			return err
		}
		if err := tx.Exec("INSERT INTO users(id,email,password_hash,created_at) VALUES(?,'legacy@example.com','fixture-hash',now())", user).Error; err != nil {
			return err
		}
		if err := tx.Exec("INSERT INTO accounts(id,user_id,name,currency,opening_balance_minor,created_at) VALUES(?,?,'Existing cash','USD',50000,now())", account, user).Error; err != nil {
			return err
		}
		if err := tx.Exec("INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at) VALUES(?,?,?,'expense',1299,'USD','Original note',now(),now())", transaction, user, account).Error; err != nil {
			return err
		}
		return tx.Exec("INSERT INTO sessions(id,user_id,token_hash,created_at,expires_at) VALUES(?,?,decode(repeat('07',32),'hex'),now(),now()+interval '1 day')", session, user).Error
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if err := Apply(ctx, sqlDB); err != nil {
		t.Fatal(err)
	}
	if err := Apply(ctx, sqlDB); err != nil {
		t.Fatal("updated migrations were not repeatable")
	}
	var userRow struct {
		Email        string
		PasswordHash string
		BaseCurrency string
	}
	if err := db.Raw("SELECT email,password_hash,base_currency FROM users WHERE id=?", user).Scan(&userRow).Error; err != nil {
		t.Fatal(err)
	}
	if userRow.Email != "legacy@example.com" || userRow.PasswordHash != "fixture-hash" || userRow.BaseCurrency != "KZT" {
		t.Fatal("migration altered existing user or wrong default preference")
	}
	var ledger struct {
		AmountMinor         int64
		Note                string
		Currency            string
		OpeningBalanceMinor int64
	}
	if err := db.Raw("SELECT t.amount_minor,t.note,t.currency,a.opening_balance_minor FROM transactions t JOIN accounts a ON a.id=t.account_id WHERE t.id=?", transaction).Scan(&ledger).Error; err != nil {
		t.Fatal(err)
	}
	if ledger.AmountMinor != 1299 || ledger.Note != "Original note" || ledger.Currency != "USD" || ledger.OpeningBalanceMinor != 50000 {
		t.Fatal("migration changed original ledger values")
	}
	var assignedCategory *string
	if err := db.Raw("SELECT category_id FROM transactions WHERE id=?", transaction).Scan(&assignedCategory).Error; err != nil {
		t.Fatal(err)
	}
	if assignedCategory != nil {
		t.Fatal("category migration invented a category for an existing transaction")
	}
	var defaultCategories int64
	if err := db.Raw("SELECT count(*) FROM categories WHERE user_id IS NULL AND is_default").Scan(&defaultCategories).Error; err != nil {
		t.Fatal(err)
	}
	if defaultCategories != 65 {
		t.Fatal("category migration did not install the complete default catalogue")
	}
	var savedChecksum string
	db.Raw("SELECT checksum FROM schema_migrations WHERE version='001_initial.sql'").Scan(&savedChecksum)
	if savedChecksum != checksum {
		t.Fatal("initial migration checksum changed")
	}
	var sessionCount int64
	db.Raw("SELECT count(*) FROM sessions WHERE id=? AND token_hash=decode(repeat('07',32),'hex')", session).Scan(&sessionCount)
	if sessionCount != 1 {
		t.Fatal("migration invalidated existing session")
	}
	var excluded bool
	if err := db.Raw("SELECT exclude_from_analytics FROM transactions WHERE id=?", transaction).Scan(&excluded).Error; err != nil || excluded {
		t.Fatal("import migration changed old transaction analytics behavior")
	}
	var importCount int64
	if err := db.Raw("SELECT count(*) FROM statement_imports").Scan(&importCount).Error; err != nil || importCount != 0 {
		t.Fatal("import migration did not create empty metadata safely")
	}
	for _, currency := range []string{"RUB", "GBP", "CHF", "AUD", "CAD", "CNY"} {
		newAccount := uuid.NewString()
		if err := db.Exec("INSERT INTO accounts(id,user_id,name,currency,opening_balance_minor,created_at) VALUES(?,?,'New currency',?,0,now())", newAccount, user, currency).Error; err != nil {
			t.Fatal("new account currency rejected")
		}
		if err := db.Exec("INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at) VALUES(?,?,?,'income',1,?,'',now(),now())", uuid.NewString(), user, newAccount, currency).Error; err != nil {
			t.Fatal("new transaction currency rejected")
		}
	}
}
