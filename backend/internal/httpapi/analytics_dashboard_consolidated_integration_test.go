package httpapi

import (
	"strconv"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

const consolidatedQuery = "?month=2026-10&currency=KZT&months=6&mode=consolidated"

func TestIntegrationConsolidatedDashboardMixedCurrenciesWholeHistoryTransfersAndScope(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	f = withFX(t, f, upstream)
	user, other := registerUser(t, f), registerUser(t, f)
	kzt := fxAccount(t, f, user.AccessToken, "KZT", "10000")
	usd := fxAccount(t, f, user.AccessToken, "USD", "100")
	eur := fxAccount(t, f, user.AccessToken, "EUR", "80")
	jpy := fxAccount(t, f, user.AccessToken, "JPY", "150")
	kwd := fxAccount(t, f, user.AccessToken, "KWD", "305")
	hidden := fxAccount(t, f, user.AccessToken, "USD", "-1")
	input := updateAccountBody(hidden)
	input.IncludeInBalance, input.Archived = false, true
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+hidden.ID, user.AccessToken, uuid.NewString(), input), 200)
	outside := fxAccount(t, f, other.AccessToken, "RUB", "10000")
	dashboardSeed(t, f, other, outside, "income", 999, "2026-10-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, usd, "income", 100, "2026-04-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, eur, "income", 80, "2026-05-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, jpy, "expense", 150, "2026-06-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, kwd, "income", 305, "2026-09-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, kzt, "income", 10000, "2026-10-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, usd, "income", 100, "2026-10-01T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, eur, "expense", 80, "2026-10-02T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, jpy, "expense", 150, "2026-10-02T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, kwd, "expense", 305, "2026-10-02T00:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, usd, "income", 100, "2026-10-03T00:00:00+05:00", nil, true)
	transfer := transferBody(kzt, usd)
	transfer.FromAmountMinor, transfer.ToAmountMinor, transfer.FeeAmountMinor = "10000", "20", "500"
	transfer.OccurredAt = "2026-10-04T12:00:00+05:00"
	w := request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), transfer)
	expectStatus(t, w, 201)
	tr := decodeResponse[service.TransferView](t, w)
	report := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	if report.Mode != "consolidated" || report.Currency != "KZT" || len(report.AccountIDs) != 6 || report.AccountID != nil || report.Timezone != "UTC+05:00" || report.PeriodStatus != "current" {
		t.Fatal("consolidated scope or calendar metadata wrong")
	}
	if report.Summary.IncomeMinor != "55000" || report.Summary.ExpenseMinor != "135500" || report.Summary.NetMinor != "-80500" || report.Summary.PreviousIncomeMinor != "45000" {
		t.Fatalf("mixed currency facts wrong: %+v", report.Summary)
	}
	if report.Series[0].OpeningBalanceMinor != "234550" || report.Series[0].ClosingBalanceMinor != "279550" || report.Series[2].IncomeMinor != "0" || report.Series[2].ExpenseMinor != "0" || report.Series[2].LedgerChangeMinor != "0" || report.Series[5].ClosingBalanceMinor != "243050" || report.Series[5].LedgerChangeMinor != "-36500" {
		t.Fatalf("history, zero month or transfer spread lost: %+v", report.Series)
	}
	for i := 1; i < len(report.Series); i++ {
		if report.Series[i].OpeningBalanceMinor != report.Series[i-1].ClosingBalanceMinor {
			t.Fatal("FX snapshot broke boundary continuity")
		}
	}
	conversion := report.Conversion
	if conversion == nil || conversion.Policy != "latest_snapshot" || conversion.Provider != "Frankfurter" || conversion.Stale || conversion.FetchedAt == nil || len(conversion.Rates) != 5 || upstream.calls.Load() != 1 {
		t.Fatal("conversion metadata/cache wrong")
	}
	for i, code := range []string{"EUR", "JPY", "KWD", "KZT", "USD"} {
		if conversion.Rates[i].Currency != code || conversion.Rates[i].Rate == "" {
			t.Fatal("account currencies missing or unsorted")
		}
		if code == "KZT" && (conversion.Rates[i].Rate != "1" || conversion.Rates[i].RateDate != nil) {
			t.Fatal("identity rate wrong")
		}
	}
	if balance := accountBalance(t, f, user.User.ID, usd.ID); balance != "420" {
		t.Fatal("read-only report mutated original USD balance")
	}
	defaults := listCategories(t, f, user.AccessToken)
	fee := findCategory(t, defaults, "Банковские комиссии")
	expectStatus(t, request(f.router, "PUT", "/api/v1/categories/"+fee.ID, user.AccessToken, uuid.NewString(), service.UpdateCategoryInput{ExpectedVersion: fee.Version, Name: "Owner fee name", Archived: true}), 200)
	rename := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	var feeSeen bool
	for _, root := range rename.Expenses {
		for _, child := range root.Children {
			if child.CategoryID != nil && *child.CategoryID == fee.ID {
				feeSeen = child.Name == "Owner fee name" && child.AmountMinor == "500"
			}
		}
	}
	if !feeSeen {
		t.Fatal("owner category override/archive lost during conversion")
	}
	original := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=USD&months=6")
	if original.Mode != "original" || original.Conversion != nil || original.Summary.IncomeMinor != "100" || original.Summary.ExpenseMinor != "0" || original.Series[5].ClosingBalanceMinor != "419" || upstream.calls.Load() != 1 {
		t.Fatal("consolidation changed original-currency behavior")
	}
	expectStatus(t, request(f.router, "DELETE", "/api/v1/transfers/"+tr.ID, user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 1}), 200)
	deleted := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	if deleted.Summary.ExpenseMinor != "135000" || deleted.Series[5].ClosingBalanceMinor != "244550" {
		t.Fatal("deleted transfer/fee remained in consolidated report")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers/"+tr.ID+"/restore", user.AccessToken, uuid.NewString(), service.ExpectedVersion{ExpectedVersion: 2}), 200)
	if restored := dashboardRead(t, f, user.AccessToken, consolidatedQuery); restored.Series[5].ClosingBalanceMinor != "243050" {
		t.Fatal("restored transfer missing from consolidated report")
	}
}

func TestIntegrationConsolidatedDashboardHalfEvenCategoriesReconcileAndZeroFactsRemain(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	upstream.body.Store(`[{"base":"USD","quote":"KZT","date":"2026-10-11","rate":0.5}]`)
	f = withFX(t, f, upstream)
	user := registerUser(t, f)
	kzt := fxAccount(t, f, user.AccessToken, "KZT", "0")
	usd := fxAccount(t, f, user.AccessToken, "USD", "0")
	fxAccount(t, f, user.AccessToken, "USD", "1")
	fxAccount(t, f, user.AccessToken, "USD", "-3")
	defaults := listCategories(t, f, user.AccessToken)
	food := findCategory(t, defaults, "Продукты и питание")
	coffee := findCategory(t, defaults, "Кофе")
	transport := findCategory(t, defaults, "Транспорт")
	dashboardSeed(t, f, user, kzt, "expense", 2, "2026-09-01T00:00:00+05:00", &transport.ID, false)
	for i := 0; i < 2; i++ {
		dashboardSeed(t, f, user, usd, "expense", 1, "2026-10-02T00:00:00+05:00", &coffee.ID, false)
	}
	dashboardSeed(t, f, user, kzt, "expense", 1, "2026-10-01T00:00:00+05:00", &food.ID, false)
	report := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	root := dashboardFind(t, report.Expenses, &food.ID)
	child := dashboardFind(t, root.Children, &coffee.ID)
	priorOnly := dashboardFind(t, report.Expenses, &transport.ID)
	if report.Summary.ExpenseMinor != "1" || root.AmountMinor != "1" || root.DirectAmountMinor != "1" || child.AmountMinor != "0" || child.DirectAmountMinor != "0" || priorOnly.AmountMinor != "0" || priorOnly.PreviousAmountMinor != "2" {
		t.Fatalf("rounding facts/category union wrong: %+v", report)
	}
	if report.Series[0].OpeningBalanceMinor != "-2" || report.Series[5].ClosingBalanceMinor != "-6" || report.Series[5].LedgerChangeMinor != "-2" || report.Series[5].NetMinor != "-1" {
		t.Fatal("per-account half-even or balance-vs-fact rounding wrong")
	}
	// Account boundary totals are rounded once, not by summing independently
	// converted movements: two half-minor expenses round to zero in facts.
	if balance := accountBalance(t, f, user.User.ID, usd.ID); balance != "-2" {
		t.Fatal("report conversion rewrote original small transactions")
	}
}

func TestIntegrationConsolidatedDashboardIdentityNoFXValidationAndKZCutoff(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	upstream.fail.Store(true)
	f = withFX(t, f, upstream)
	user := registerUser(t, f)
	empty := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	if empty.Conversion == nil || len(empty.Conversion.Rates) != 0 || empty.Conversion.FetchedAt != nil || empty.Conversion.Stale || upstream.calls.Load() != 0 {
		t.Fatal("empty report requested FX")
	}
	a := fxAccount(t, f, user.AccessToken, "KZT", "-15")
	dashboardSeed(t, f, user, a, "income", 10, "2026-09-30T18:59:59Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 20, "2026-09-30T19:00:00Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 30, "2026-10-31T18:59:59Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 40, "2026-10-31T19:00:00Z", nil, false)
	report := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	if report.Summary.IncomeMinor != "50" || report.Summary.PreviousIncomeMinor != "10" || report.Series[5].ClosingBalanceMinor != "45" || report.Conversion.FetchedAt != nil || report.Conversion.Stale || len(report.Conversion.Rates) != 1 || upstream.calls.Load() != 0 {
		t.Fatal("identity report requested FX or changed KZ/future date rules")
	}
	for _, query := range []string{
		"?month=2026-10&currency=KZT&months=6&mode=wrong",
		"?month=2026-10&currency=KZT&months=6&mode=consolidated&account_id=" + a.ID,
		"?month=2026-10&currency=USD&months=6&mode=consolidated",
	} {
		expectStatus(t, request(f.router, "GET", "/api/v1/analytics/dashboard"+query, user.AccessToken, "", nil), 400)
	}
	for _, count := range []int{3, 6, 12} {
		for _, month := range []string{"1901-01", "2199-12"} {
			read := dashboardRead(t, f, user.AccessToken, "?month="+month+"&currency=KZT&months="+strconv.Itoa(count)+"&mode=consolidated")
			if len(read.Series) != count || read.Conversion.FetchedAt != nil {
				t.Fatal("boundary window invalid")
			}
		}
	}
	expectStatus(t, request(f.router, "GET", "/api/v1/analytics/dashboard"+consolidatedQuery, "", "", nil), 401)
}

func TestIntegrationConsolidatedDashboardMissingStaleAndExpiredRatesAreExplicit(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	f = withFX(t, f, upstream)
	user := registerUser(t, f)
	fxAccount(t, f, user.AccessToken, "KZT", "100")
	fxAccount(t, f, user.AccessToken, "USD", "100")
	fresh := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	upstream.fail.Store(true)
	upstream.clock.Advance(7 * time.Hour)
	stale := dashboardRead(t, f, user.AccessToken, consolidatedQuery)
	if !stale.Conversion.Stale || stale.Conversion.FetchedAt == nil || !stale.Conversion.FetchedAt.Equal(*fresh.Conversion.FetchedAt) || stale.Series[5].ClosingBalanceMinor != fresh.Series[5].ClosingBalanceMinor {
		t.Fatal("usable stale snapshot hidden or mixed")
	}
	fxAccount(t, f, user.AccessToken, "RUB", "0")
	w := request(f.router, "GET", "/api/v1/analytics/dashboard"+consolidatedQuery, user.AccessToken, "", nil)
	expectStatus(t, w, 503)
	if decodeResponse[struct{ Error struct{ Code string } }](t, w).Error.Code != "rates_unavailable" {
		t.Fatal("missing required pair returned a partial report")
	}
	upstream.clock.Advance(8 * 24 * time.Hour)
	w = request(f.router, "GET", "/api/v1/analytics/dashboard"+consolidatedQuery, user.AccessToken, "", nil)
	expectStatus(t, w, 503)
}

func TestIntegrationConsolidatedDashboardConversionAndAggregateOverflow(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	upstream.body.Store(`[{"base":"USD","quote":"KZT","date":"2026-10-11","rate":100000}]`)
	f = withFX(t, f, upstream)
	user := registerUser(t, f)
	usd := fxAccount(t, f, user.AccessToken, "USD", "0")
	dashboardSeed(t, f, user, usd, "income", service.MaxMinor, "2026-10-01T00:00:00+05:00", nil, false)
	w := request(f.router, "GET", "/api/v1/analytics/dashboard"+consolidatedQuery, user.AccessToken, "", nil)
	expectStatus(t, w, 409)
	if decodeResponse[struct{ Error struct{ Code string } }](t, w).Error.Code != "analytics_overflow" {
		t.Fatal("conversion overflow used the wrong code")
	}
	// Every source fact fits int64 and converts without overflow, but summing
	// the valid converted facts must still reject rather than wrap.
	if err := f.db.Exec("DELETE FROM transactions WHERE user_id=?", user.User.ID).Error; err != nil {
		t.Fatal(err)
	}
	upstream.clock.Advance(7 * time.Hour)
	upstream.body.Store(`[{"base":"USD","quote":"KZT","date":"2026-10-11","rate":1000}]`)
	for i := 0; i < 10; i++ {
		dashboardSeed(t, f, user, usd, "income", service.MaxMinor, "2026-10-01T00:00:00+05:00", nil, false)
		dashboardSeed(t, f, user, usd, "expense", service.MaxMinor, "2026-10-01T00:00:00+05:00", nil, false)
	}
	w = request(f.router, "GET", "/api/v1/analytics/dashboard"+consolidatedQuery, user.AccessToken, "", nil)
	expectStatus(t, w, 409)
	if decodeResponse[struct{ Error struct{ Code string } }](t, w).Error.Code != "analytics_overflow" {
		t.Fatal("aggregate overflow did not return analytics_overflow")
	}
}
