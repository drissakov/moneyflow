package httpapi

import (
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

// Imported own-account movements affect the ledger balance while staying out of
// income/expense charts. Ordinary purchases, fees and earnings still contribute.
func TestIntegrationMovementBalanceAndAnalytics(t *testing.T) {
	f := integrationFixture(t)
	user := registerUser(t, f)
	account := createAccount(t, f, user.AccessToken, "1000")
	defaults := listCategories(t, f, user.AccessToken)
	coffee := findCategory(t, defaults, "Кофе")
	salary := findCategory(t, defaults, "Зарплата")
	at := time.Date(2026, 10, 13, 12, 0, 0, 0, time.UTC)
	for _, row := range []struct {
		kind     string
		amount   int64
		category *string
		excluded bool
	}{
		{"income", 10000, &salary.ID, false},
		{"expense", 2000, &coffee.ID, false},
		{"income", 50000, &salary.ID, true},
		{"expense", 70000, &coffee.ID, true},
		{"expense", 300, nil, false},
	} {
		if err := f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: user.User.ID,
			AccountID: account.ID, Kind: row.kind, AmountMinor: row.amount, Currency: "KZT",
			OccurredAt: at, CreatedAt: at, CategoryID: row.category, ExcludeFromAnalytics: row.excluded}).Error; err != nil {
			t.Fatal(err)
		}
	}
	w := request(f.router, "GET", "/api/v1/accounts", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	accounts := decodeResponse[struct {
		Accounts []service.AccountView `json:"accounts"`
	}](t, w)
	if len(accounts.Accounts) != 1 || accounts.Accounts[0].BalanceMinor != "-11300" {
		t.Fatal("movements were lost from the account ledger")
	}
	w = request(f.router, "GET", "/api/v1/analytics?month=2026-10&currency=KZT&timezone=Asia%2FQyzylorda", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	view := decodeResponse[service.AnalyticsView](t, w)
	if view.IncomeMinor != "10000" || view.ExpenseMinor != "2300" || len(view.Expenses) != 2 {
		t.Fatal("own-account movements incorrectly contributed to income/expense charts")
	}
	w = request(f.router, "GET", "/api/v1/transactions?account_id="+account.ID, user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	transactions := decodeResponse[struct {
		Transactions []service.TransactionView `json:"transactions"`
	}](t, w)
	if len(transactions.Transactions) != 5 {
		t.Fatal("movements must remain visible in transaction history")
	}
	excluded := 0
	for _, item := range transactions.Transactions {
		if item.ExcludeFromAnalytics {
			excluded++
		}
	}
	if excluded != 2 {
		t.Fatal("transaction view lost movement classification")
	}
}
