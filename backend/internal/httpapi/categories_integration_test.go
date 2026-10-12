package httpapi

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"math"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

func listCategories(t *testing.T, f fixture, token string) []service.CategoryView {
	t.Helper()
	w := request(f.router, "GET", "/api/v1/categories", token, "", nil)
	expectStatus(t, w, 200)
	return decodeResponse[struct{ Categories []service.CategoryView }](t, w).Categories
}

func findCategory(t *testing.T, categories []service.CategoryView, name string) service.CategoryView {
	t.Helper()
	for _, c := range categories {
		if c.Name == name {
			return c
		}
	}
	t.Fatalf("category %q missing", name)
	return service.CategoryView{}
}

func createCategory(t *testing.T, f fixture, token, kind, name string, parent *string) service.CategoryView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/categories", token, "", map[string]any{"kind": kind, "name": name, "parent_id": parent})
	expectStatus(t, w, 201)
	return decodeResponse[service.CategoryView](t, w)
}

func TestIntegrationCategoriesHierarchyAndPrivacy(t *testing.T) {
	f := integrationFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	defaults := listCategories(t, f, user.AccessToken)
	var expenseRoots, expenseChildren, incomeRoots int
	for _, c := range defaults {
		if !c.IsDefault {
			t.Fatal("default catalogue contained a custom category")
		}
		if c.Kind == "income" {
			incomeRoots++
			if c.ParentID != nil {
				t.Fatal("income default has a parent")
			}
		} else if c.ParentID == nil {
			expenseRoots++
		} else {
			expenseChildren++
		}
	}
	if expenseRoots != 14 || incomeRoots != 10 || expenseChildren != 41 {
		t.Fatalf("incomplete default catalogue: roots=%d income=%d children=%d", expenseRoots, incomeRoots, expenseChildren)
	}
	food := findCategory(t, defaults, "Продукты и питание")
	coffee := findCategory(t, defaults, "Кофе")
	salary := findCategory(t, defaults, "Зарплата")
	car := createCategory(t, f, user.AccessToken, "expense", "  Покупка   автомобиля ", nil)
	if car.Name != "Покупка автомобиля" || car.IsDefault || car.ParentID != nil {
		t.Fatal("custom category was not normalized or was marked default")
	}
	cigarettes := createCategory(t, f, user.AccessToken, "expense", "Сигареты", &food.ID)
	if cigarettes.ParentID == nil || *cigarettes.ParentID != food.ID {
		t.Fatal("custom child not linked to standard food root")
	}
	createCategory(t, f, user.AccessToken, "expense", "Доставка автомобиля", &car.ID)
	createCategory(t, f, user.AccessToken, "income", "Продажа авторских работ", nil)
	for _, category := range listCategories(t, f, other.AccessToken) {
		if !category.IsDefault {
			t.Fatal("private category leaked to another user")
		}
	}
	for _, tc := range []struct {
		kind, name string
		parent     *string
		status     int
	}{
		{"expense", " продукты и питание ", nil, 409},
		{"expense", " сигареты ", &food.ID, 409},
		{"expense", "Child", &coffee.ID, 400},
		{"expense", "Child", &salary.ID, 400},
		{"income", "Child", &salary.ID, 400},
		{"expense", "Child", ptr(uuid.NewString()), 404},
		{"expense", "", nil, 400},
	} {
		expectStatus(t, request(f.router, "POST", "/api/v1/categories", user.AccessToken, "", map[string]any{
			"kind": tc.kind, "name": tc.name, "parent_id": tc.parent,
		}), tc.status)
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/categories", other.AccessToken, "", map[string]any{
		"kind": "expense", "name": "Private parent", "parent_id": car.ID,
	}), 404)
	// Sibling names are scoped to their parent. Other users can independently
	// create the same root name without seeing the original author's category.
	createCategory(t, f, user.AccessToken, "expense", "Сигареты", &car.ID)
	createCategory(t, f, other.AccessToken, "expense", "Покупка автомобиля", nil)
	expectStatus(t, request(f.router, "GET", "/api/v1/categories", "", "", nil), 401)
	// Database protection also rejects bypasses of application validation.
	if err := f.db.Create(&model.Category{ID: uuid.NewString(), UserID: &other.User.ID, Kind: "expense", Name: "Bypass", ParentID: &car.ID, CreatedAt: time.Now()}).Error; err == nil {
		t.Fatal("database allowed another user's private parent")
	}
}

func ptr(s string) *string { return &s }

func TestIntegrationTransactionCategoriesAssignmentAndLegacyRetries(t *testing.T) {
	f := integrationFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "10000")
	defaults := listCategories(t, f, user.AccessToken)
	food := findCategory(t, defaults, "Продукты и питание")
	coffee := findCategory(t, defaults, "Кофе")
	salary := findCategory(t, defaults, "Зарплата")
	private := createCategory(t, f, other.AccessToken, "expense", "Private", nil)
	body := transactionBody(a.ID, "expense", "500")
	body["category_id"] = coffee.ID
	key := uuid.NewString()
	w := request(f.router, "POST", "/api/v1/transactions", user.AccessToken, key, body)
	expectStatus(t, w, 201)
	created := decodeResponse[service.TransactionView](t, w)
	if created.CategoryID == nil || *created.CategoryID != coffee.ID {
		t.Fatal("category was not persisted in created transaction")
	}
	original := append([]byte(nil), w.Body.Bytes()...)
	body["category_id"] = food.ID
	expectStatus(t, request(f.router, "POST", "/api/v1/transactions", user.AccessToken, key, body), 409)
	for _, categoryID := range []string{salary.ID, private.ID, uuid.NewString(), "invalid"} {
		body["category_id"] = categoryID
		status := 404
		if categoryID == salary.ID || categoryID == "invalid" {
			status = 400
		}
		expectStatus(t, request(f.router, "POST", "/api/v1/transactions", user.AccessToken, uuid.NewString(), body), status)
	}
	path := "/api/v1/transactions/" + created.ID + "/category"
	expectStatus(t, request(f.router, "PUT", path, other.AccessToken, "", map[string]any{"category_id": food.ID}), 404)
	expectStatus(t, request(f.router, "PUT", path, user.AccessToken, "", map[string]any{"category_id": private.ID}), 404)
	expectStatus(t, request(f.router, "PUT", path, user.AccessToken, "", map[string]any{"category_id": salary.ID}), 400)
	expectStatus(t, request(f.router, "PUT", path, user.AccessToken, "", map[string]any{}), 200)
	w = request(f.router, "PUT", path, user.AccessToken, "", map[string]any{"category_id": food.ID})
	expectStatus(t, w, 200)
	updated := decodeResponse[service.TransactionView](t, w)
	if updated.CategoryID == nil || *updated.CategoryID != food.ID || updated.AmountMinor != created.AmountMinor || !updated.CreatedAt.Equal(created.CreatedAt) {
		t.Fatal("reclassification altered financial transaction values")
	}
	w = request(f.router, "PUT", path, user.AccessToken, "", map[string]any{"category_id": nil})
	expectStatus(t, w, 200)
	if decodeResponse[service.TransactionView](t, w).CategoryID != nil {
		t.Fatal("category was not cleared")
	}
	body["category_id"] = coffee.ID
	w = request(f.router, "POST", "/api/v1/transactions", user.AccessToken, key, body)
	expectStatus(t, w, 201)
	if !bytes.Equal(original, w.Body.Bytes()) {
		t.Fatal("reclassification changed durable idempotency response")
	}
	// Simulate an idempotency record from release two: the canonical input and
	// persisted response omitted category_id entirely. Both omitted and null
	// category requests must replay that exact response after migration.
	legacyBody := transactionBody(a.ID, "expense", "100")
	legacyKey := uuid.NewString()
	w = request(f.router, "POST", "/api/v1/transactions", user.AccessToken, legacyKey, legacyBody)
	expectStatus(t, w, 201)
	var oldResponse map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &oldResponse); err != nil {
		t.Fatal(err)
	}
	delete(oldResponse, "category_id")
	oldBytes, _ := json.Marshal(oldResponse)
	input, err := service.ValidateTransaction(a.ID, "expense", "100", "Test entry", "2026-10-09T12:00:00+05:00")
	if err != nil {
		t.Fatal(err)
	}
	canonical, _ := json.Marshal(input)
	fingerprint := sha256.Sum256(append([]byte("POST /api/v1/transactions\n"), canonical...))
	if err := f.db.Model(&model.IdempotencyRecord{}).Where("user_id = ? AND key = ?", user.User.ID, legacyKey).
		Updates(map[string]any{"response_body": oldBytes, "request_hash": fingerprint[:]}).Error; err != nil {
		t.Fatal(err)
	}
	for _, retryBody := range []any{legacyBody, map[string]any{
		"account_id": a.ID, "kind": "expense", "amount_minor": "100", "note": "Test entry", "occurred_at": "2026-10-09T12:00:00+05:00", "category_id": nil,
	}} {
		w = request(f.router, "POST", "/api/v1/transactions", user.AccessToken, legacyKey, retryBody)
		expectStatus(t, w, 201)
		if !bytes.Equal(oldBytes, w.Body.Bytes()) {
			t.Fatal("legacy retry changed persisted response")
		}
	}
	var count int64
	f.db.Model(&model.Transaction{}).Where("account_id = ?", a.ID).Count(&count)
	if count != 2 {
		t.Fatal("failed category assignment or retry created an extra transaction")
	}
}

func TestIntegrationMonthlyAnalyticsCurrencyTimezoneAndCompleteLedger(t *testing.T) {
	f := integrationFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "0")
	b := createAccount(t, f, other.AccessToken, "0")
	defaults := listCategories(t, f, user.AccessToken)
	food := findCategory(t, defaults, "Продукты и питание")
	coffee := findCategory(t, defaults, "Кофе")
	groceries := findCategory(t, defaults, "Продукты")
	salary := findCategory(t, defaults, "Зарплата")
	cigarettes := createCategory(t, f, user.AccessToken, "expense", "Сигареты", &food.ID)
	start, end, err := service.AnalyticsPeriod("2026-10", "KZT", "Asia/Qyzylorda")
	if err != nil {
		t.Fatal(err)
	}
	insert := func(userID, accountID, kind, currency string, amount int64, at time.Time, categoryID *string) {
		t.Helper()
		if err := f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: userID, AccountID: accountID, Kind: kind,
			Currency: currency, AmountMinor: amount, OccurredAt: at, CreatedAt: at, CategoryID: categoryID}).Error; err != nil {
			t.Fatal(err)
		}
	}
	for _, item := range []struct {
		amount int64
		id     *string
	}{{50, &food.ID}, {40, &coffee.ID}, {60, &groceries.ID}, {20, &cigarettes.ID}, {10, nil}} {
		insert(user.User.ID, a.ID, "expense", "KZT", item.amount, start.Add(time.Hour), item.id)
	}
	// Old uncategorized entries beyond the recent transaction list's default 50
	// limit must all contribute to monthly analytics.
	for i := 0; i < 55; i++ {
		insert(user.User.ID, a.ID, "expense", "KZT", 1, start.Add(time.Hour), nil)
	}
	insert(user.User.ID, a.ID, "expense", "KZT", 3, start, &groceries.ID)
	insert(user.User.ID, a.ID, "expense", "KZT", 9999, start.Add(-time.Microsecond), &food.ID)
	insert(user.User.ID, a.ID, "expense", "KZT", 9999, end, &food.ID)
	insert(user.User.ID, a.ID, "income", "KZT", 1000, start.Add(time.Hour), &salary.ID)
	insert(other.User.ID, b.ID, "expense", "KZT", 9999, start.Add(time.Hour), &food.ID)
	w := request(f.router, "POST", "/api/v1/accounts", user.AccessToken, "", map[string]string{"name": "Dollars", "currency": "USD", "opening_balance_minor": "0"})
	expectStatus(t, w, 201)
	usd := decodeResponse[service.AccountView](t, w)
	insert(user.User.ID, usd.ID, "expense", "USD", 777, start.Add(time.Hour), &coffee.ID)
	w = request(f.router, "GET", "/api/v1/analytics?month=2026-10&currency=KZT&timezone=Asia%2FQyzylorda", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	view := decodeResponse[service.AnalyticsView](t, w)
	if view.ExpenseMinor != "238" || view.IncomeMinor != "1000" || len(view.Expenses) != 2 || len(view.Income) != 1 {
		t.Fatalf("wrong complete ledger totals: %#v", view)
	}
	root := view.Expenses[0]
	if root.CategoryID == nil || *root.CategoryID != food.ID || root.AmountMinor != "173" || len(root.Children) != 3 || root.Children[0].AmountMinor != "63" {
		t.Fatalf("root/child rollup or order wrong: %#v", root)
	}
	if view.Expenses[1].CategoryID != nil || view.Expenses[1].Name != "Без категории" || view.Expenses[1].AmountMinor != "65" {
		t.Fatal("uncategorized history was lost")
	}
	w = request(f.router, "GET", "/api/v1/analytics?month=2026-10&currency=USD&timezone=Asia%2FQyzylorda", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	if decodeResponse[service.AnalyticsView](t, w).ExpenseMinor != "777" {
		t.Fatal("native currency filter mixed USD and KZT")
	}
	w = request(f.router, "GET", "/api/v1/analytics?month=2026-12&currency=KZT&timezone=UTC", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	empty := decodeResponse[service.AnalyticsView](t, w)
	if empty.ExpenseMinor != "0" || empty.IncomeMinor != "0" || empty.Income == nil || empty.Expenses == nil {
		t.Fatal("empty month representation invalid")
	}
	expectStatus(t, request(f.router, "GET", "/api/v1/analytics?month=2026-10&currency=KZT&timezone=UTC", "", "", nil), 401)
	expectStatus(t, request(f.router, "GET", "/api/v1/analytics?month=2026-10&currency=KZT", user.AccessToken, "", nil), 200)
	for _, query := range []string{"month=2026-10&currency=BTC&timezone=UTC", "month=2026-10&currency=KZT&timezone=Local", "month=2026-13&currency=KZT&timezone=UTC", "month=2026-10&currency=KZT&timezone="} {
		expectStatus(t, request(f.router, "GET", "/api/v1/analytics?"+query, user.AccessToken, "", nil), 400)
	}
}

func TestIntegrationAnalyticsOverflowReturnsExplicitError(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	a := createAccount(t, f, user.AccessToken, "0")
	whole := int64(math.MaxInt64)/service.MaxMinor + 1
	if err := f.db.Exec(`INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at)
		SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'expense', ?, 'KZT', '', '2026-10-15T12:00:00Z', now()
		FROM generate_series(1, ?)`, user.User.ID, a.ID, service.MaxMinor, whole).Error; err != nil {
		t.Fatal(err)
	}
	w := request(f.router, "GET", "/api/v1/analytics?month=2026-10&currency=KZT&timezone=UTC", user.AccessToken, "", nil)
	expectStatus(t, w, 409)
	if !bytes.Contains(w.Body.Bytes(), []byte(`"code":"analytics_overflow"`)) {
		t.Fatal("overflow did not return documented analytics error")
	}
}
