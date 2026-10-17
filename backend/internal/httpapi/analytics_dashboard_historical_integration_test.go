package httpapi

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/fx"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

const historicalQuery = "?month=2026-10&currency=KZT&months=3&mode=consolidated&valuation=historical"

type dashboardHistoryProvider struct {
	now          time.Time
	calls        atomic.Int64
	fail         atomic.Bool
	missingKZT   bool
	rateOverride string
}

func (p *dashboardHistoryProvider) FetchRange(_ context.Context, from, to time.Time) (*fx.HistoricalSnapshot, error) {
	p.calls.Add(1)
	if p.fail.Load() {
		return nil, fmt.Errorf("fixture upstream unavailable")
	}
	result := &fx.HistoricalSnapshot{FetchedAt: p.now, FromDate: from.Format(time.DateOnly), ToDate: to.Format(time.DateOnly), Quotes: make(map[string][]fx.Quote)}
	for date := from; !date.After(to); date = date.AddDate(0, 0, 1) {
		if date.Weekday() == time.Saturday || date.Weekday() == time.Sunday {
			continue
		}
		key := date.Format(time.DateOnly)
		rate := fmt.Sprint(int(date.Month()) * 100)
		if date.Month() == time.October && date.Day() >= 10 {
			rate = "1100"
		}
		if p.rateOverride != "" {
			rate = p.rateOverride
		}
		if !p.missingKZT {
			result.Quotes["KZT"] = append(result.Quotes["KZT"], fx.Quote{Date: key, Rate: rate})
		}
		result.Quotes["EUR"] = append(result.Quotes["EUR"], fx.Quote{Date: key, Rate: "0.5"})
	}
	return result, nil
}

func withDashboardHistory(t *testing.T, f fixture, p *dashboardHistoryProvider) fixture {
	t.Helper()
	p.now = time.Date(2026, 10, 17, 10, 0, 0, 0, time.UTC)
	s, err := service.New(f.db, time.Hour, service.Options{HistoricalFXProvider: p, Now: func() time.Time { return p.now }})
	if err != nil {
		t.Fatal(err)
	}
	router, err := New(s, Options{Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	f.service, f.router = s, router
	return f
}

func TestIntegrationHistoricalDashboardOperationDatesBoundariesFeesAndCurrentCutoff(t *testing.T) {
	f := integrationFixture(t)
	p := &dashboardHistoryProvider{}
	f = withDashboardHistory(t, f, p)
	user, other := registerUser(t, f), registerUser(t, f)
	kzt := fxAccount(t, f, user.AccessToken, "KZT", "1000")
	usd := fxAccount(t, f, user.AccessToken, "USD", "90")
	outside := fxAccount(t, f, other.AccessToken, "EUR", "999")
	dashboardSeed(t, f, other, outside, "income", 999, "2026-10-01T12:00:00+05:00", nil, false)
	// Whole history contributes to opening even if account creation was later.
	dashboardSeed(t, f, user, usd, "income", 10, "2026-01-02T12:00:00+05:00", nil, false)
	defaults := listCategories(t, f, user.AccessToken)
	food, coffee := findCategory(t, defaults, "Продукты и питание"), findCategory(t, defaults, "Кофе")
	dashboardSeed(t, f, user, usd, "expense", 10, "2026-08-03T12:00:00+05:00", &food.ID, false)
	dashboardSeed(t, f, user, usd, "income", 20, "2026-09-05T12:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, usd, "income", 5, "2026-09-10T12:00:00+05:00", nil, true)
	dashboardSeed(t, f, user, usd, "expense", 3, "2026-09-30T19:00:00Z", &coffee.ID, false)
	dashboardSeed(t, f, user, usd, "income", 7, "2026-10-12T12:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, usd, "income", 1, "2026-10-17T10:00:00Z", nil, false)
	dashboardSeed(t, f, user, usd, "income", 2, "2026-10-16T12:00:00+05:00", nil, true)
	// Exactly as_of is included; even one microsecond later is excluded.
	dashboardSeed(t, f, user, usd, "income", 100, "2026-10-17T10:00:00.000001Z", nil, false)
	dashboardSeed(t, f, user, usd, "expense", 200, "2026-10-17T20:00:00+05:00", nil, false)
	dashboardSeed(t, f, user, usd, "income", 300, "2026-10-31T12:00:00+05:00", nil, false)
	transfer := transferBody(kzt, usd)
	transfer.FromAmountMinor, transfer.ToAmountMinor, transfer.FeeAmountMinor = "100", "1", "5"
	transfer.OccurredAt = "2026-10-04T12:00:00+05:00"
	expectStatus(t, request(f.router, "POST", "/api/v1/transfers", user.AccessToken, uuid.NewString(), transfer), 201)
	report := dashboardRead(t, f, user.AccessToken, historicalQuery)
	if report.Summary.IncomeMinor != "8800" || report.Summary.ExpenseMinor != "3005" || report.Summary.PreviousIncomeMinor != "18000" || report.Summary.NetMinor != "5795" {
		t.Fatalf("operation-date facts wrong: %+v", report.Summary)
	}
	if len(report.AccountIDs) != 2 || report.Series[0].OpeningBalanceMinor != "71000" {
		t.Fatal("owner scope or full-history opening wrong")
	}
	for i, want := range []struct{ close, change, movement, revaluation, date string }{
		{"73000", "2000", "-8000", "10000", "2026-08-31"},
		{"104500", "31500", "22500", "9000", "2026-09-30"},
		{"136195", "31695", "8895", "22800", "2026-10-17"},
	} {
		point := report.Series[i]
		if point.ClosingBalanceMinor != want.close || point.LedgerChangeMinor != want.change || point.ConvertedLedgerChangeMinor != want.movement || point.FXRevaluationMinor != want.revaluation || point.ValuationDate != want.date {
			t.Fatalf("historical month %d wrong: %+v", i, point)
		}
		if i > 0 && (point.OpeningBalanceMinor != report.Series[i-1].ClosingBalanceMinor || point.OpeningValuationDate != report.Series[i-1].ValuationDate) {
			t.Fatal("historical boundaries are discontinuous")
		}
	}
	root := dashboardFind(t, report.Expenses, &food.ID)
	child := dashboardFind(t, root.Children, &coffee.ID)
	if root.AmountMinor != "3000" || root.DirectAmountMinor != "0" || child.AmountMinor != "3000" {
		t.Fatal("operation-date category totals disagree")
	}
	c := report.Conversion
	if c == nil || c.Policy != "historical" || c.FromDate != "2026-07-31" || c.ToDate != "2026-10-17" || c.MaxAgeDays != 14 || c.Stale || c.FetchedAt == nil || p.calls.Load() != 1 {
		t.Fatal("historical metadata/cache wrong")
	}
	var sunday, today bool
	previous := ""
	for _, rate := range c.Rates {
		key := rate.ValuationDate + ":" + rate.Currency
		if key <= previous || rate.Currency == "KZT" || rate.TargetRateDate == nil || rate.RateDate == nil || rate.SourceRateDate != nil {
			t.Fatal("used FX dates/identity sorting wrong")
		}
		if rate.ValuationDate == "2026-10-04" {
			sunday = *rate.TargetRateDate == "2026-10-02" && rate.Rate == "1000"
		}
		if rate.ValuationDate == "2026-10-17" {
			today = *rate.TargetRateDate == "2026-10-16" && rate.Rate == "1100"
		}
		previous = key
	}
	if !sunday || !today {
		t.Fatal("prior published day not used on weekend")
	}
	response := request(f.router, "GET", "/api/v1/analytics/dashboard"+historicalQuery, user.AccessToken, "", nil)
	if !strings.Contains(response.Body.String(), `"source_rate_date":null`) {
		t.Fatal("USD mathematical anchor null missing")
	}
	// A past selection closes at month end, with no current-month leakage.
	past := dashboardRead(t, f, user.AccessToken, "?month=2026-09&currency=KZT&months=3&mode=consolidated&valuation=historical")
	if past.PeriodStatus != "past" || past.Series[2].ValuationDate != "2026-09-30" || past.Series[2].ClosingBalanceMinor != "104500" || past.Conversion.ToDate != "2026-09-30" {
		t.Fatal("past cutoff wrong")
	}
	if p.calls.Load() != 1 {
		t.Fatal("history book was not cached")
	}
	// Refresh failure may use a complete historical book, with its provenance
	// visible; it must never replace those dates with today's snapshot.
	p.now = p.now.Add(7 * time.Hour)
	p.fail.Store(true)
	stale := dashboardRead(t, f, user.AccessToken, "?month=2026-09&currency=KZT&months=3&mode=consolidated&valuation=historical")
	if !stale.Conversion.Stale || !stale.Conversion.FetchedAt.Equal(*past.Conversion.FetchedAt) || stale.Series[2].ClosingBalanceMinor != past.Series[2].ClosingBalanceMinor || p.calls.Load() != 2 {
		t.Fatal("complete stale historical provenance was hidden or changed")
	}
}

func TestIntegrationHistoricalDashboardIdentityValidationAndLatestCompatibility(t *testing.T) {
	f := integrationFixture(t)
	p := &dashboardHistoryProvider{}
	p.fail.Store(true)
	f = withDashboardHistory(t, f, p)
	user := registerUser(t, f)
	empty := dashboardRead(t, f, user.AccessToken, historicalQuery)
	if len(empty.Conversion.Rates) != 0 || empty.Conversion.FetchedAt != nil || p.calls.Load() != 0 {
		t.Fatal("empty scope queried historical FX")
	}
	a := fxAccount(t, f, user.AccessToken, "KZT", "-5")
	dashboardSeed(t, f, user, a, "income", 10, "2026-10-17T10:00:00Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 20, "2026-10-17T10:00:00.000001Z", nil, false)
	dashboardSeed(t, f, user, a, "income", 30, "2026-10-31T12:00:00+05:00", nil, false)
	history := dashboardRead(t, f, user.AccessToken, historicalQuery)
	if history.Summary.IncomeMinor != "10" || history.Series[2].ClosingBalanceMinor != "5" || history.Series[2].FXRevaluationMinor != "0" || history.Conversion.FetchedAt != nil || len(history.Conversion.Rates) != 0 || p.calls.Load() != 0 {
		t.Fatal("KZT current cutoff or no-network identity wrong")
	}
	latest := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3&mode=consolidated")
	if latest.Conversion.Policy != "latest_snapshot" || latest.Summary.IncomeMinor != "60" || latest.Series[2].ValuationDate != "" {
		t.Fatal("omitted valuation no longer backward-compatible")
	}
	original := dashboardRead(t, f, user.AccessToken, "?month=2026-10&currency=KZT&months=3")
	if original.Summary.IncomeMinor != "60" || original.Conversion != nil {
		t.Fatal("historical implementation changed original")
	}
	for _, query := range []string{
		"?month=2026-11&currency=KZT&months=3&mode=consolidated&valuation=historical",
		"?month=2026-10&currency=KZT&months=3&valuation=historical",
		"?month=2026-10&currency=KZT&months=3&valuation=latest_snapshot",
		"?month=2026-10&currency=KZT&months=3&mode=consolidated&valuation=nope",
		"?month=2026-10&currency=KZT&months=3&mode=consolidated&valuation=historical&account_id=" + a.ID,
		"?month=2026-10&currency=USD&months=3&mode=consolidated&valuation=historical",
	} {
		expectStatus(t, request(f.router, "GET", "/api/v1/analytics/dashboard"+query, user.AccessToken, "", nil), 400)
	}
	expectStatus(t, request(f.router, "GET", "/api/v1/analytics/dashboard"+historicalQuery, "", "", nil), 401)
}

func TestIntegrationHistoricalDashboardHalfEvenPerAccountAndNonUSDAnchor(t *testing.T) {
	f := integrationFixture(t)
	p := &dashboardHistoryProvider{rateOverride: "0.5"}
	f = withDashboardHistory(t, f, p)
	user := registerUser(t, f)
	usd := fxAccount(t, f, user.AccessToken, "USD", "0")
	fxAccount(t, f, user.AccessToken, "USD", "1")
	hidden := fxAccount(t, f, user.AccessToken, "USD", "-3")
	input := updateAccountBody(hidden)
	input.IncludeInBalance, input.Archived = false, true
	expectStatus(t, request(f.router, "PUT", "/api/v1/accounts/"+hidden.ID, user.AccessToken, uuid.NewString(), input), 200)
	eur := fxAccount(t, f, user.AccessToken, "EUR", "1")
	defaults := listCategories(t, f, user.AccessToken)
	coffee := findCategory(t, defaults, "Кофе")
	for i := 0; i < 2; i++ {
		dashboardSeed(t, f, user, usd, "expense", 1, "2026-10-02T12:00:00+05:00", &coffee.ID, false)
	}
	dashboardSeed(t, f, user, eur, "income", 1, "2026-10-04T12:00:00+05:00", nil, false)
	report := dashboardRead(t, f, user.AccessToken, historicalQuery)
	if report.Series[0].OpeningBalanceMinor != "-1" || report.Series[2].ClosingBalanceMinor != "-1" || report.Series[2].ConvertedLedgerChangeMinor != "1" || report.Series[2].FXRevaluationMinor != "-1" || report.Summary.IncomeMinor != "1" || report.Summary.ExpenseMinor != "0" {
		t.Fatalf("half even or anchor division wrong: %+v", report)
	}
	food := findCategory(t, defaults, "Продукты и питание")
	if dashboardFind(t, dashboardFind(t, report.Expenses, &food.ID).Children, &coffee.ID).AmountMinor != "0" {
		t.Fatal("rounded zero expense disappeared")
	}
	var eurSeen bool
	for _, rate := range report.Conversion.Rates {
		if rate.Currency == "EUR" && rate.ValuationDate == "2026-10-04" {
			eurSeen = rate.SourceRateDate != nil && *rate.SourceRateDate == "2026-10-02" && rate.TargetRateDate != nil && *rate.TargetRateDate == "2026-10-02" && rate.Rate == "1"
		}
	}
	if !eurSeen {
		t.Fatal("non-USD actual anchor dates missing")
	}
}

func TestIntegrationHistoricalDashboardUnavailableAndOverflow(t *testing.T) {
	for _, test := range []struct {
		name, rate string
		missing    bool
		status     int
	}{
		{"missing", "", true, 503}, {"conversion_overflow", "1000000", false, 409}, {"aggregate_overflow", "1100", false, 409},
	} {
		t.Run(test.name, func(t *testing.T) {
			f := integrationFixture(t)
			p := &dashboardHistoryProvider{rateOverride: test.rate, missingKZT: test.missing}
			f = withDashboardHistory(t, f, p)
			user := registerUser(t, f)
			usd := fxAccount(t, f, user.AccessToken, "USD", "0")
			count := 1
			if test.name == "aggregate_overflow" {
				count = 10
			}
			for i := 0; i < count; i++ {
				dashboardSeed(t, f, user, usd, "income", service.MaxMinor, "2026-10-01T12:00:00+05:00", nil, false)
				dashboardSeed(t, f, user, usd, "expense", service.MaxMinor, "2026-10-01T12:00:00+05:00", nil, false)
			}
			w := request(f.router, "GET", "/api/v1/analytics/dashboard"+historicalQuery, user.AccessToken, "", nil)
			expectStatus(t, w, test.status)
			code := decodeResponse[struct{ Error struct{ Code string } }](t, w).Error.Code
			if test.status == 503 && code != "rates_unavailable" || test.status == 409 && code != "analytics_overflow" {
				t.Fatal("incorrect failure code")
			}
		})
	}
}
