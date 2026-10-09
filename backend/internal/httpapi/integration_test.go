package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/database"
	"github.com/drissakov/moneyflow/backend/internal/migrations"
	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type fixture struct {
	router  http.Handler
	service *service.Service
	db      *gorm.DB
}

func integrationFixture(t *testing.T) fixture {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("set TEST_DATABASE_URL to run real PostgreSQL integration tests")
	}
	control, err := database.Open(dsn)
	if err != nil {
		t.Fatal("test database connection failed")
	}
	controlSQL, err := control.DB()
	if err != nil {
		t.Fatal(err)
	}
	schema := "moneyflow_test_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if err := control.Exec("CREATE SCHEMA " + schema).Error; err != nil {
		t.Fatal("create isolated test schema failed")
	}
	t.Cleanup(func() {
		control.Exec("DROP SCHEMA " + schema + " CASCADE")
		controlSQL.Close()
	})
	if strings.HasPrefix(dsn, "postgres://") || strings.HasPrefix(dsn, "postgresql://") {
		parsed, err := url.Parse(dsn)
		if err != nil {
			t.Fatal("invalid TEST_DATABASE_URL")
		}
		query := parsed.Query()
		query.Set("search_path", schema)
		parsed.RawQuery = query.Encode()
		dsn = parsed.String()
	} else {
		dsn += " search_path=" + schema
	}
	db, err := database.Open(dsn)
	if err != nil {
		t.Fatal("isolated test database connection failed")
	}
	sqlDB, err := db.DB()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { sqlDB.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	if err := migrations.Apply(ctx, sqlDB); err != nil {
		t.Fatal(err)
	}
	// Re-running immutable migrations must be a no-op.
	if err := migrations.Apply(ctx, sqlDB); err != nil {
		t.Fatal("migration was not repeatable")
	}
	s, err := service.New(db, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	gin.SetMode(gin.TestMode)
	router, err := New(s, Options{Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	return fixture{router: router, service: s, db: db}
}

func request(router http.Handler, method, path, token, key string, body any) *httptest.ResponseRecorder {
	var encoded []byte
	if raw, ok := body.(string); ok {
		encoded = []byte(raw)
	} else if body != nil {
		encoded, _ = json.Marshal(body)
	}
	req := httptest.NewRequest(method, path, bytes.NewReader(encoded))
	req.RemoteAddr = "127.0.0.1:54321"
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if key != "" {
		req.Header.Set("Idempotency-Key", key)
	}
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)
	return w
}

func expectStatus(t *testing.T, w *httptest.ResponseRecorder, status int) {
	t.Helper()
	if w.Code != status {
		var errorBody struct {
			Error struct{ Code string } `json:"error"`
		}
		json.Unmarshal(w.Body.Bytes(), &errorBody)
		t.Fatalf("HTTP status = %d; want %d (code=%s)", w.Code, status, errorBody.Error.Code)
	}
}

func decodeResponse[T any](t *testing.T, w *httptest.ResponseRecorder) T {
	t.Helper()
	var result T
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatal("invalid JSON response")
	}
	return result
}

func registerUser(t *testing.T, f fixture) service.AuthView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/auth/register", "", "", map[string]string{
		"email": uuid.NewString() + "@example.com", "password": "test-password-with-enough-bytes",
	})
	expectStatus(t, w, 201)
	return decodeResponse[service.AuthView](t, w)
}

func createAccount(t *testing.T, f fixture, token, opening string) service.AccountView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/accounts", token, "", map[string]string{
		"name": "Cash", "currency": "KZT", "opening_balance_minor": opening,
	})
	expectStatus(t, w, 201)
	return decodeResponse[service.AccountView](t, w)
}

func transactionBody(accountID, kind, amount string) map[string]string {
	return map[string]string{
		"account_id": accountID, "kind": kind, "amount_minor": amount,
		"note": "Test entry", "occurred_at": "2026-10-09T12:00:00+05:00",
	}
}

func TestIntegrationAuthOwnershipAndExactBalances(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	other := registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "1000000000000000")
	for i := 0; i < 9; i++ {
		w := request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(a.ID, "income", "1000000000000000"))
		expectStatus(t, w, 201)
	}
	w := request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(a.ID, "expense", "1299"))
	expectStatus(t, w, 201)
	created := decodeResponse[service.TransactionView](t, w)
	if created.AmountMinor != "1299" || created.Currency != "KZT" || created.OccurredAt.Format(time.RFC3339) != "2026-10-09T07:00:00Z" {
		t.Fatal("transaction values or timestamp changed")
	}
	w = request(f.router, "GET", "/api/v1/accounts", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	accounts := decodeResponse[struct{ Accounts []service.AccountView }](t, w)
	if len(accounts.Accounts) != 1 || accounts.Accounts[0].BalanceMinor != "9999999999998701" {
		t.Fatalf("exact derived balance was lost: %#v", accounts.Accounts)
	}
	w = request(f.router, "GET", "/api/v1/accounts", other.AccessToken, "", nil)
	expectStatus(t, w, 200)
	if !strings.Contains(w.Body.String(), `"accounts":[]`) {
		t.Fatal("empty list is not a JSON array or another user's account leaked")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions", other.AccessToken, uuid.NewString(), transactionBody(a.ID, "expense", "1")), 404)
	expectStatus(t, request(f.router, "GET", "/api/v1/transactions?account_id="+a.ID, other.AccessToken, "", nil), 404)
	w = request(f.router, "GET", "/api/v1/transactions?account_id="+a.ID+"&limit=3", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	items := decodeResponse[struct{ Transactions []service.TransactionView }](t, w)
	if len(items.Transactions) != 3 || items.Transactions[0].ID != created.ID {
		t.Fatal("recent ordering or limit failed")
	}
	expectStatus(t, request(f.router, "GET", "/api/v1/me", "", "", nil), 401)
	expectStatus(t, request(f.router, "GET", "/api/v1/me", user.AccessToken, "", nil), 200)
	expectStatus(t, request(f.router, "POST", "/api/v1/auth/logout", user.AccessToken, "", nil), 204)
	expectStatus(t, request(f.router, "GET", "/api/v1/me", user.AccessToken, "", nil), 401)
	w = request(f.router, "POST", "/api/v1/auth/login", "", "", map[string]string{"email": user.User.Email, "password": "test-password-with-enough-bytes"})
	expectStatus(t, w, 200)
	login := decodeResponse[service.AuthView](t, w)
	if login.AccessToken == user.AccessToken || login.User.ID != user.User.ID {
		t.Fatal("login did not issue an independent session")
	}
	var stored model.Session
	if err := f.db.Where("user_id = ? AND revoked_at IS NULL", user.User.ID).First(&stored).Error; err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(stored.TokenHash, []byte(login.AccessToken)) || len(stored.TokenHash) != 32 {
		t.Fatal("session token was not hashed")
	}
	f.db.Model(&model.Session{}).Where("id = ?", stored.ID).Updates(map[string]any{
		"created_at": time.Now().Add(-2 * time.Hour), "expires_at": time.Now().Add(-time.Hour),
	})
	expectStatus(t, request(f.router, "GET", "/api/v1/me", login.AccessToken, "", nil), 401)
	expectStatus(t, request(f.router, "GET", "/readyz", "", "", nil), 200)
}

func TestIntegrationConcurrentIdempotencyAndDurability(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "10000")
	key := uuid.NewString()
	body := transactionBody(a.ID, "expense", "1299")
	results := make(chan *httptest.ResponseRecorder, 8)
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			results <- request(f.router, "POST", "/api/v1/transactions", user.AccessToken, key, body)
		}()
	}
	group.Wait()
	close(results)
	var original []byte
	for result := range results {
		expectStatus(t, result, 201)
		if original == nil {
			original = append([]byte(nil), result.Body.Bytes()...)
		} else if !bytes.Equal(original, result.Body.Bytes()) {
			t.Fatal("duplicate response differs from persisted original")
		}
	}
	var count int64
	f.db.Model(&model.Transaction{}).Where("account_id = ?", a.ID).Count(&count)
	if count != 1 {
		t.Fatalf("retries created %d transactions", count)
	}
	// Recreate the application service/router to ensure replay is database-backed.
	s, err := service.New(f.db, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	router, err := New(s, Options{Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	w := request(router, "POST", "/api/v1/transactions", user.AccessToken, key, body)
	expectStatus(t, w, 201)
	if !bytes.Equal(original, w.Body.Bytes()) {
		t.Fatal("restart lost idempotency response")
	}
	body["amount_minor"] = "1300"
	expectStatus(t, request(router, "POST", "/api/v1/transactions", user.AccessToken, key, body), 409)
	views, err := f.service.Accounts(context.Background(), user.User.ID)
	if err != nil || len(views) != 1 || views[0].BalanceMinor != "8701" {
		t.Fatal("duplicate/conflicting retries changed the balance")
	}
}

func TestIntegrationStrictPayloadAndAmountValidation(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "0")
	for _, amount := range []string{"0", "-1", "1.01", "1e2", "01", "1000000000000001"} {
		expectStatus(t, request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), transactionBody(a.ID, "expense", amount)), 400)
	}
	for _, body := range []string{
		`{"name":"Cash","currency":"USD","opening_balance_minor":"0","user_id":"evil"}`,
		`{"name":"Cash","currency":"USD","opening_balance_minor":0}`,
		`{"name":"Cash","currency":"USD","opening_balance_minor":"0"} {}`,
		`{"name":`, `[]`, `null`,
	} {
		expectStatus(t, request(f.router, "POST", "/api/v1/accounts", user.AccessToken, "", body), 400)
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions", user.AccessToken, "", transactionBody(a.ID, "expense", "1")), 400)
	expectStatus(t, request(f.router, "GET", "/api/v1/transactions?account_id="+a.ID+"&limit=101", user.AccessToken, "", nil), 400)
	expectStatus(t, request(f.router, "GET", "/api/v1/transactions?account_id=not-a-uuid", user.AccessToken, "", nil), 400)
	expectStatus(t, request(f.router, "POST", "/api/v1/accounts", user.AccessToken, "", `{"name":"`+strings.Repeat("x", 17000)+`"}`), 413)
	expectStatus(t, request(f.router, "POST", "/api/v1/auth/login", "", "", map[string]string{"email": user.User.Email, "password": "wrong-password-with-enough-bytes"}), 401)
	expectStatus(t, request(f.router, "POST", "/api/v1/auth/login", "", "", map[string]string{"email": "missing@example.com", "password": "wrong-password-with-enough-bytes"}), 401)
	expectStatus(t, request(f.router, "POST", "/api/v1/auth/register", "", "", map[string]string{"email": strings.ToUpper(user.User.Email), "password": "test-password-with-enough-bytes"}), 409)
}

func TestIntegrationBalanceOverflowRollsBackOperation(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "0")
	// Build a valid history close to MaxInt64 using legal individual amounts.
	whole := int64(math.MaxInt64-5) / service.MaxMinor
	remainder := int64(math.MaxInt64-5) % service.MaxMinor
	err := f.db.Exec(`INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at)
		SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'income', ?, 'KZT', '', now(), now()
		FROM generate_series(1, ?)`, user.User.ID, a.ID, service.MaxMinor, whole).Error
	if err != nil {
		t.Fatal(err)
	}
	err = f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: a.ID,
		Kind: "income", AmountMinor: remainder, Currency: "KZT", OccurredAt: time.Now(), CreatedAt: time.Now()}).Error
	if err != nil {
		t.Fatal(err)
	}
	key := uuid.NewString()
	w := request(f.router, "POST", "/api/v1/transactions", user.AccessToken, key, transactionBody(a.ID, "income", "6"))
	expectStatus(t, w, 409)
	var recorded int64
	f.db.Model(&model.IdempotencyRecord{}).Where("user_id = ? AND key = ?", user.User.ID, key).Count(&recorded)
	if recorded != 0 {
		t.Fatal("rejected operation reserved an idempotency key")
	}
	// The failed key remains usable because its transaction was rolled back.
	w = request(f.router, "POST", "/api/v1/transactions", user.AccessToken, key, transactionBody(a.ID, "income", "5"))
	expectStatus(t, w, 201)
	views, err := f.service.Accounts(context.Background(), user.User.ID)
	if err != nil || len(views) != 1 || views[0].BalanceMinor != strconv.FormatInt(math.MaxInt64, 10) {
		t.Fatal("maximum exact balance was not preserved")
	}
	var count int64
	f.db.Model(&model.Transaction{}).Where("account_id = ?", a.ID).Count(&count)
	if count != whole+2 {
		t.Fatal(fmt.Sprintf("rollback left an unexpected transaction count: %d", count))
	}
}
