package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"math"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

func dashboardRead(t *testing.T, f fixture, token, query string) service.AnalyticsDashboardView {
	t.Helper()
	w := request(f.router, "GET", "/api/v1/analytics/dashboard"+query, token, "", nil)
	expectStatus(t, w, 200)
	return decodeResponse[service.AnalyticsDashboardView](t, w)
}
func dashboardSeed(t *testing.T, f fixture, user service.AuthView, a service.AccountView, kind string, amount int64, at string, category *string, excluded bool) model.Transaction {
	t.Helper()
	when, err := time.Parse(time.RFC3339, at)
	if err != nil {
		t.Fatal(err)
	}
	row := model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: a.ID, Kind: kind, AmountMinor: amount, Currency: a.Currency, CategoryID: category, ExcludeFromAnalytics: excluded, OccurredAt: when, CreatedAt: time.Now().UTC(), Version: 1}
	if err := f.db.Create(&row).Error; err != nil {
		t.Fatal(err)
	}
	return row
}
func dashboardFind(t *testing.T, buckets []service.AnalyticsComparisonBucket, id *string) service.AnalyticsComparisonBucket {
	t.Helper()
	for _, b := range buckets {
		if b.CategoryID == nil && id == nil || b.CategoryID != nil && id != nil && *b.CategoryID == *id {
			return b
		}
	}
	t.Fatal("missing dashboard category")
	return service.AnalyticsComparisonBucket{}
}

func TestIntegrationDashboardZeroMonthsHistoricalBaselineAndScope(t *testing.T) {
	f := planningFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "KZT")
	usd := newLedgerAccount(t, f, user, "USD")
	outside := newLedgerAccount(t, f, other, "KZT")
	ua := updateAccountBody(a)
	ua.OpeningBalanceMinor = "1000"
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+a.ID, user.AccessToken, uuid.NewString(), ua), 200)
	ub := updateAccountBody(b)
	ub.OpeningBalanceMinor = "2000"
	ub.Archived = true
	ub.IncludeInBalance = false
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+b.ID, user.AccessToken, uuid.NewString(), ub), 200)
	dashboardSeed(t, f, user, a, "income", 400, "2026-01-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, a, "expense", 100, "2026-08-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, b, "income", 1000, "2026-09-10T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, b, "expense", 400, "2026-09-20T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, a, "income", 700, "2026-10-05T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, b, "expense", 300, "2026-10-06T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, a, "income", 500, "2026-10-10T00:00:00+05:00", nil, true)
	dashboardSeed(t, f, user, usd, "income", 999, "2026-10-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, other, outside, "income", 999, "2026-10-01T00:00:00+05:00", nil, false)
	report := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3")
	if report.Months != 3 || len(report.Series) != 3 || report.Timezone != "UTC+05:00" || report.Today != "2026-10-17" || report.PeriodStatus != "current" || len(report.AccountIDs) != 2 || report.AccountID != nil {
		t.Fatalf("scope/time metadata %+v", report)
	}
	if report.From != "2026-10-01T00:00:00+05:00" || report.To != "2026-11-01T00:00:00+05:00" {
		t.Fatal("selected month boundaries wrong")
	}
	if report.Series[0].Month != "2026-08" || report.Series[0].OpeningBalanceMinor != "3400" || report.Series[0].ClosingBalanceMinor != "3300" || report.Series[2].OpeningBalanceMinor != "3900" || report.Series[2].ClosingBalanceMinor != "4800" || report.Series[2].LedgerChangeMinor != "900" || report.Series[2].NetMinor != "400" {
		t.Fatalf("historical balances %+v", report.Series)
	}
	if report.Summary.IncomeMinor != "700" || report.Summary.ExpenseMinor != "300" || report.Summary.PreviousIncomeMinor != "1000" || report.Summary.PreviousExpenseMinor != "400" || report.Summary.IncomeDeltaMinor != "-300" || report.Summary.ExpenseDeltaMinor != "-100" || report.Summary.NetDeltaMinor != "-200" {
		t.Fatalf("comparison %+v", report.Summary)
	}
	selected := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=12&account_id="+b.ID)
	if len(selected.Series) != 12 || selected.Series[0].Month != "2025-11" || selected.Series[0].IncomeMinor != "0" || selected.Series[0].ClosingBalanceMinor != "2000" || selected.Summary.IncomeMinor != "0" || selected.Summary.ExpenseMinor != "300" || len(selected.AccountIDs) != 1 || selected.AccountIDs[0] != b.ID {
		t.Fatal("archived singleaccount or zero months lost")
	}
	empty := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=JPY&months=6")
	if len(empty.AccountIDs) != 0 || empty.Summary.IncomeMinor != "0" || empty.Series[5].ClosingBalanceMinor != "0" {
		t.Fatal("empty currency fabricated balance")
	}
	expectStatus(t, request(f.router, "GET", "/api/v1/analytics/dashboard?month=2026-10&currency=KZT&months=3&account_id="+outside.ID, user.AccessToken, "", nil), 404)
	expectStatus(t, request(f.router, "GET", "/api/v1/analytics/dashboard?month=2026-10&currency=KZT&months=3&account_id="+usd.ID, user.AccessToken, "", nil), 400)
}

func TestIntegrationDashboardCategoriesReconcileSubtreeExactAndUncategorizedHistory(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "KZT")
	defaults := listCategories(t, f, user.AccessToken)
	food := findCategory(t, defaults, "Продукты и питание")
	coffee := findCategory(t, defaults, "Кофе")
	grocery := findCategory(t, defaults, "Продукты")
	salary := findCategory(t, defaults, "Зарплата")
	dashboardSeed(t, f, user, a, "expense", 10, "2026-09-10T12:00:00+05:00", &food.ID, false)
	dashboardSeed(t, f, user, a, "expense", 20, "2026-09-10T12:00:00+05:00", &grocery.ID, false)
	dashboardSeed(t, f, user, a, "expense", 50, "2026-10-01T00:00:00+05:00", &food.ID, false)
	var rows []model.Transaction
	at, _ := time.Parse(time.RFC3339, "2026-10-12T12:00:00+05:00")
	for i := 0; i < 137; i++ {
		account := a
		if i%2 == 1 {
			account = b
		}
		rows = append(rows, model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: account.ID, Currency: "KZT", Kind: "expense", AmountMinor: 1, OccurredAt: at, CreatedAt: at, CategoryID: &coffee.ID, Version: 1})
	}
	if err := f.db.Create(&rows).Error; err != nil {
		t.Fatal(err)
	}
	dashboardSeed(t, f, user, a, "expense", 7, "2026-10-15T12:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, a, "expense", 999, "2026-10-15T12:00:00+05:00", &food.ID, true)
	dashboardSeed(t, f, user, a, "income", 1000, "2026-10-15T12:00:00+05:00", &salary.ID, false)
	report := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=6")
	root := dashboardFind(t, report.Expenses, &food.ID)
	if root.AmountMinor != "187" || root.DirectAmountMinor != "50" || root.PreviousAmountMinor != "30" || root.DeltaMinor != "157" || len(root.Children) != 2 {
		t.Fatalf("category drill structure %+v", root)
	}
	oldOnly := dashboardFind(t, root.Children, &grocery.ID)
	if oldOnly.AmountMinor != "0" || oldOnly.PreviousAmountMinor != "20" || oldOnly.DeltaMinor != "-20" {
		t.Fatal("prior-only child discarded")
	}
	base := "?currency=KZT&analytics_only=true&kind=expense&from=" + url.QueryEscape(report.From) + "&to=" + url.QueryEscape(report.To)
	sums := func(query string) (int64, int) {
		t.Helper()
		total, count := int64(0), 0
		cursor := ""
		for {
			q := query + "&limit=17"
			if cursor != "" {
				q += "&cursor=" + url.QueryEscape(cursor)
			}
			page := ledgerHistory(t, f, user.AccessToken, q)
			for _, r := range page.Transactions {
				n, _ := strconv.ParseInt(r.AmountMinor, 10, 64)
				total += n
				count++
				if r.ExcludeFromAnalytics || r.DeletedAt != nil {
					t.Fatal("analytics drill included excluded record")
				}
			}
			if page.NextCursor == nil {
				break
			}
			cursor = *page.NextCursor
		}
		return total, count
	}
	if sum, count := sums(base + "&category_id=" + food.ID); sum != 187 || count != 138 {
		t.Fatal("root drill does not reconcile", sum, count)
	}
	if sum, count := sums(base + "&category_id=" + food.ID + "&category_exact=true"); sum != 50 || count != 1 {
		t.Fatal("directroot drill includes children")
	}
	if sum, count := sums(base + "&category_id=" + coffee.ID); sum != 137 || count != 137 {
		t.Fatal("child drill lost pagination")
	}
	if sum, count := sums(base + "&uncategorized=true"); sum != 7 || count != 1 {
		t.Fatal("uncategorized drill does not reconcile")
	}
	page := ledgerHistory(t, f, user.AccessToken, base+"&category_id="+coffee.ID+"&limit=17")
	expectStatus(t, request(f.router, "GET", "/api/v1/transactions"+base+"&category_id="+food.ID+"&limit=17&cursor="+url.QueryEscape(*page.NextCursor), user.AccessToken, "", nil), 400)
}

func TestIntegrationDashboardKazakhstanMonthEdgesAndStoredFutureDates(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	dashboardSeed(t, f, user, a, "income", 10, "2026-09-30T18:59:59Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 20, "2026-09-30T19:00:00Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 30, "2026-10-31T18:59:59Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 40, "2026-10-31T19:00:00Z", nil, false)
	current := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3")
	if current.Summary.IncomeMinor != "50" || current.Summary.PreviousIncomeMinor != "10" || current.PeriodStatus != "current" {
		t.Fatal("KZ cutoff or currentfutureposted rows lost")
	}
	future := dashboardRead(t, f, user.AccessToken, "?month=2026-11&currency=KZT&months=6")
	if future.PeriodStatus != "future" || future.Summary.IncomeMinor != "40" {
		t.Fatal("future month mislabeled")
	}
	past := dashboardRead(t, f, user.AccessToken, "?month=2026-09&currency=KZT&months=12")
	if past.PeriodStatus != "past" || past.Summary.IncomeMinor != "10" {
		t.Fatal("past month mislabeled")
	}
	for _, month := range []string{"1901-01", "2199-12"} {
		report := dashboardRead(t, f, user.AccessToken, "?month="+month+"&currency=KZT&months=12")
		if len(report.Series) != 12 {
			t.Fatal("boundary year lost series")
		}
	}
}

func TestIntegrationDashboardTransfersFeesDeletedRestoreAndCategoryOverrides(t *testing.T) {
	f := planningFixture(t)
	user := registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	b := newLedgerAccount(t, f, user, "KZT")
	usd := newLedgerAccount(t, f, user, "USD")
	transfer := transferBody(a, b)
	transfer.FromAmountMinor = "1000"
	transfer.ToAmountMinor = "1000"
	transfer.FeeAmountMinor = "10"
	transfer.OccurredAt = "2026-10-15T12:00:00+05:00"
	w := request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), transfer)
	expectStatus(t, w, 201)
	tr := decodeResponse[service.TransferView](t, w)
	cross := transferBody(a, usd)
	cross.FromAmountMinor = "4500"
	cross.ToAmountMinor = "1000"
	cross.FeeAmountMinor = "0"
	cross.FeeAccountID = nil
	cross.OccurredAt = "2026-10-16T12:00:00+05:00"
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), cross), 201)
	report := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3")
	if report.Summary.IncomeMinor != "0" || report.Summary.ExpenseMinor != "10" || report.Series[2].LedgerChangeMinor != "-4510" || report.Series[2].ClosingBalanceMinor != "195490" {
		t.Fatal("principal included as expense or balance lost crosscurrency leg")
	}
	defaults := listCategories(t, f, user.AccessToken)
	fee := findCategory(t, defaults, "Банковские комиссии")
	expectStatus(t, request(f.router, "PUT", "/api/v1/categories/"+fee.ID, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: fee.Version, Name: "Fee renamed", Archived: true}), 200)
	renamed := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3")
	feeRoot := renamed.Expenses[0]
	if len(feeRoot.Children) != 1 || feeRoot.Children[0].Name != "Fee renamed" {
		t.Fatal("archived peruser category name lost")
	}
	expectStatus(t, request(f.router, "DELETE", "/api/v1/transfers/"+tr.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1}), 200)
	deleted := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3")
	if deleted.Summary.ExpenseMinor != "0" || deleted.Series[2].LedgerChangeMinor != "-4500" {
		t.Fatal("softdeleted transfer remained in reports")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers/"+tr.ID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 2}), 200)
	restored := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3")
	if restored.Summary.ExpenseMinor != "10" {
		t.Fatal("restored fee lost")
	}
}

func TestIntegrationDashboardFilterValidationLegacyCursorAndOverflow(t *testing.T) {
	f := planningFixture(t)
	user, other := registerUser(t, f), registerUser(t, f)
	a := newLedgerAccount(t, f, user, "KZT")
	usd := newLedgerAccount(t, f, user, "USD")
	outside := newLedgerAccount(t, f, other, "KZT")
	for _, query := range []string{"?month=2026-13&currency=KZT&months=3", "?month=1900-12&currency=KZT&months=3", "?month=2026-10&currency=KZT&months=4", "?month=2026-10&currency=BAD&months=3", "?month=2026-10&currency=KZT&months=no"} {
		expectStatus(t, request(f.router, "GET", "/api/v1/analytics/dashboard"+query, user.AccessToken, "", nil), 400)
	}
	for _, query := range []string{"?currency=BAD", "?analytics_only=oops", "?analytics_only=true&include_deleted=true", "?uncategorized=true&category_id=" + uuid.NewString(), "?category_exact=true", "?account_id=" + usd.ID + "&currency=KZT"} {
		expectStatus(t, request(f.router, "GET", "/api/v1/transactions"+query, user.AccessToken, "", nil), 400)
	}
	expectStatus(t, request(f.router, "GET", "/api/v1/transactions?currency=KZT&account_id="+outside.ID, user.AccessToken, "", nil), 404)
	dashboardSeed(t, f, user, a, "income", 1, "2026-10-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, a, "income", 2, "2026-10-02T00:00:00+05:00", nil, false)
	page := ledgerHistory(t, f, user.AccessToken, "?limit=1")
	raw, err := base64.RawURLEncoding.DecodeString(*page.NextCursor)
	if err != nil {
		t.Fatal(err)
	}
	var cursor map[string]any
	if err := json.Unmarshal(raw, &cursor); err != nil {
		t.Fatal(err)
	}
	filter := cursor["f"].(string)
	for _, name := range []string{"currency", "analytics_only", "uncategorized", "category_exact"} {
		if strings.Contains(filter, "\""+name+"\"") {
			t.Fatal("legacy default cursor fingerprint changed")
		}
	}
	if len(ledgerHistory(t, f, user.AccessToken, "?limit=1&cursor="+url.QueryEscape(*page.NextCursor)).Transactions) != 1 {
		t.Fatal("legacy cursor continuation failed")
	}
	// Every financial input fits MaxMinor and matching expenses keep the actual
	// balance stable. The separate income/expense chart totals can still overflow.
	whole := int64(math.MaxInt64)/service.MaxMinor + 1
	if err := f.db.Exec(`INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at)
 SELECT gen_random_uuid(),?::uuid,?::uuid,k.kind,?,'KZT','','2026-10-03T00:00:00+05:00',now()
 FROM generate_series(1,?) CROSS JOIN (VALUES('income'),('expense')) AS k(kind)`, user.User.ID, a.ID, service.MaxMinor, whole).Error; err != nil {
		t.Fatal(err)
	}
	importErrorCode(t, request(f.router, "GET", "/api/v1/analytics/dashboard?month=2026-10&currency=KZT&months=3", user.AccessToken, "", nil), 409, "analytics_overflow")
}
