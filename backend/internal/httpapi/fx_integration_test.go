package httpapi

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/fx"
	"github.com/drissakov/moneyflow/backend/internal/model"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/google/uuid"
)

type fxClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *fxClock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.now }
func (c *fxClock) Advance(duration time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(duration)
}

type fakeFrankfurter struct {
	server *httptest.Server
	clock  *fxClock
	calls  atomic.Int64
	fail   atomic.Bool
	body   atomic.Value
}

func newFakeFrankfurter(t *testing.T) *fakeFrankfurter {
	t.Helper()
	upstream := &fakeFrankfurter{clock: &fxClock{now: time.Date(2026, 10, 11, 12, 0, 0, 0, time.UTC)}}
	upstream.body.Store(`[{"base":"USD","quote":"KZT","date":"2026-10-09","rate":450},{"base":"USD","quote":"EUR","date":"2026-10-10","rate":0.8},{"base":"USD","quote":"JPY","date":"2026-10-11","rate":150},{"base":"USD","quote":"KWD","date":"2026-10-11","rate":0.305}]`)
	upstream.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstream.calls.Add(1)
		if r.URL.Path != "/v2/rates" || r.URL.Query().Get("base") != "USD" || len(r.URL.Query()) != 2 || r.Header.Get("Authorization") != "" {
			t.Error("unexpected upstream request or private data exposure")
		}
		if upstream.fail.Load() {
			w.WriteHeader(503)
			return
		}
		fmt.Fprint(w, upstream.body.Load().(string))
	}))
	t.Cleanup(upstream.server.Close)
	return upstream
}

func withFX(t *testing.T, f fixture, upstream *fakeFrankfurter) fixture {
	t.Helper()
	provider, err := fx.NewFrankfurter(upstream.server.URL + "/v2")
	if err != nil {
		t.Fatal(err)
	}
	provider.Now = upstream.clock.Now
	s, err := service.New(f.db, time.Hour, service.Options{FXProvider: provider, Now: upstream.clock.Now})
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

func fxAccount(t *testing.T, f fixture, token, currency, opening string) service.AccountView {
	t.Helper()
	w := request(f.router, "POST", "/api/v1/accounts", token, "", map[string]string{"name": "Account " + currency, "currency": currency, "opening_balance_minor": opening})
	expectStatus(t, w, 201)
	return decodeResponse[service.AccountView](t, w)
}

func TestIntegrationFXPreferencesPortfolioAndOwnerIsolation(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	f = withFX(t, f, upstream)
	user, other := registerUser(t, f), registerUser(t, f)
	for _, pair := range [][2]string{{"KZT", "10000"}, {"USD", "100"}, {"EUR", "80"}, {"JPY", "150"}, {"KWD", "305"}} {
		fxAccount(t, f, user.AccessToken, pair[0], pair[1])
	}
	for _, currency := range []string{"RUB", "GBP", "CHF", "AUD", "CAD", "CNY"} {
		fxAccount(t, f, other.AccessToken, currency, "0")
	}
	w := request(f.router, "GET", "/api/v1/preferences", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	if decodeResponse[service.PreferencesView](t, w).BaseCurrency != "KZT" {
		t.Fatal("new user preference is not KZT")
	}
	if upstream.calls.Load() != 0 {
		t.Fatal("ordinary auth/account/preferences operations requested FX")
	}
	w = request(f.router, "GET", "/api/v1/currencies", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	var catalog struct {
		Provider   string
		Currencies []fx.Currency
	}
	catalog = decodeResponse[struct {
		Provider   string
		Currencies []fx.Currency
	}](t, w)
	if len(catalog.Currencies) != 11 || catalog.Provider != "Frankfurter" {
		t.Fatal("catalogue incomplete")
	}
	w = request(f.router, "GET", "/api/v1/portfolio", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	portfolio := decodeResponse[service.PortfolioView](t, w)
	if !portfolio.Complete || portfolio.TotalBalanceMinor == nil || *portfolio.TotalBalanceMinor != "190000" || portfolio.PartialBalanceMinor != "190000" || len(portfolio.Accounts) != 5 || len(portfolio.MissingCurrencies) != 0 {
		t.Fatalf("cross-scale portfolio wrong:%#v", portfolio)
	}
	if portfolio.FetchedAt == nil || portfolio.Stale {
		t.Fatal("fresh snapshot metadata wrong")
	}
	for _, a := range portfolio.Accounts {
		if a.Currency == "KZT" && (a.Rate == nil || *a.Rate != "1" || a.RateDate != nil) {
			t.Fatal("identity account metadata wrong")
		}
		if a.Currency != "KZT" && (a.RateDate == nil || *a.RateDate != "2026-10-09") {
			t.Fatal("cross-rate date must use older contributing observation")
		}
	}
	w = request(f.router, "PUT", "/api/v1/preferences", user.AccessToken, "", map[string]string{"base_currency": "USD"})
	expectStatus(t, w, 200)
	w = request(f.router, "GET", "/api/v1/preferences", other.AccessToken, "", nil)
	expectStatus(t, w, 200)
	if decodeResponse[service.PreferencesView](t, w).BaseCurrency != "KZT" {
		t.Fatal("preference leaked across users")
	}
	w = request(f.router, "GET", "/api/v1/portfolio", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	portfolio = decodeResponse[service.PortfolioView](t, w)
	if portfolio.BaseCurrency != "USD" || portfolio.TotalBalanceMinor == nil || *portfolio.TotalBalanceMinor != "422" {
		t.Fatal("portfolio ignored persistent preference")
	}
	if upstream.calls.Load() != 1 {
		t.Fatal("shared fresh anchor was fetched more than once")
	}
	expectStatus(t, request(f.router, "PUT", "/api/v1/preferences", user.AccessToken, "", map[string]string{"base_currency": "BTC"}), 400)
	expectStatus(t, request(f.router, "PUT", "/api/v1/preferences", user.AccessToken, "", `{"base_currency":"EUR","user_id":"evil"}`), 400)
	expectStatus(t, request(f.router, "GET", "/api/v1/portfolio?base=BTC", user.AccessToken, "", nil), 400)
	expectStatus(t, request(f.router, "GET", "/api/v1/currencies", "", "", nil), 401)
	expectStatus(t, request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", map[string]string{"from_currency": "BTC", "to_currency": "USD", "amount_minor": "1"}), 400)
	// Account balances and transaction currency stay in their original units.
	accounts, err := f.service.Accounts(context.Background(), user.User.ID)
	if err != nil || len(accounts) != 5 {
		t.Fatal("conversion changed the ledger")
	}
	var usd *service.AccountView
	for i := range accounts {
		if accounts[i].Currency == "USD" {
			usd = &accounts[i]
			break
		}
	}
	if usd == nil || usd.BalanceMinor != "100" {
		t.Fatal("conversion changed the USD ledger balance")
	}
}

func TestIntegrationFXPersistentCacheRefreshDedupStaleAndExpiry(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	f = withFX(t, f, upstream)
	user := registerUser(t, f)
	fxAccount(t, f, user.AccessToken, "KZT", "10000")
	fxAccount(t, f, user.AccessToken, "USD", "100")
	body := map[string]string{"from_currency": "USD", "to_currency": "JPY", "amount_minor": "100"}
	var group sync.WaitGroup
	results := make(chan *httptest.ResponseRecorder, 8)
	for i := 0; i < 8; i++ {
		group.Add(1)
		go func() {
			defer group.Done()
			results <- request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", body)
		}()
	}
	group.Wait()
	close(results)
	for result := range results {
		expectStatus(t, result, 200)
		if decodeResponse[service.ConversionView](t, result).ConvertedAmountMinor != "150" {
			t.Fatal("cross-scale conversion wrong")
		}
	}
	if upstream.calls.Load() != 1 {
		t.Fatalf("concurrent refresh requested%d snapshots", upstream.calls.Load())
	}
	upstream.fail.Store(true)
	f = withFX(t, f, upstream) // A restarted application reads the persisted cache.
	w := request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", body)
	expectStatus(t, w, 200)
	if upstream.calls.Load() != 1 || decodeResponse[service.ConversionView](t, w).Stale {
		t.Fatal("restart did not reuse fresh persistent snapshot")
	}
	upstream.clock.Advance(7 * time.Hour)
	w = request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", body)
	expectStatus(t, w, 200)
	conversion := decodeResponse[service.ConversionView](t, w)
	if !conversion.Stale || conversion.FetchedAt == nil || conversion.RateDate == nil || *conversion.RateDate != "2026-10-11" {
		t.Fatal("expired cache did not produce explicit stale metadata on outage")
	}
	if upstream.calls.Load() != 2 {
		t.Fatal("expired cache did not attempt refresh")
	}
	expectStatus(t, request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", body), 200)
	if upstream.calls.Load() != 2 {
		t.Fatal("outage retry cooldown ignored")
	}
	fxAccount(t, f, user.AccessToken, "RUB", "10000")
	w = request(f.router, "GET", "/api/v1/portfolio?base=KZT", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	portfolio := decodeResponse[service.PortfolioView](t, w)
	if portfolio.Complete || portfolio.TotalBalanceMinor != nil || portfolio.PartialBalanceMinor != "55000" || strings.Join(portfolio.MissingCurrencies, ",") != "RUB" {
		t.Fatal("partial portfolio fabricated a full total")
	}
	upstream.clock.Advance(8 * 24 * time.Hour)
	w = request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", body)
	expectStatus(t, w, 503)
	w = request(f.router, "GET", "/api/v1/portfolio?base=KZT", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	portfolio = decodeResponse[service.PortfolioView](t, w)
	if portfolio.Complete || portfolio.TotalBalanceMinor != nil || portfolio.PartialBalanceMinor != "10000" || portfolio.FetchedAt != nil || strings.Join(portfolio.MissingCurrencies, ",") != "USD,RUB" {
		t.Fatal("too-old snapshot remained usable or leaked unknown totals")
	}
	w = request(f.router, "GET", "/api/v1/exchange-rates?base=KZT", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	rates := decodeResponse[service.ExchangeRatesView](t, w)
	if len(rates.Rates) != 1 || rates.Rates[0].QuoteCurrency != "KZT" || rates.Rates[0].Rate != "1" || rates.Rates[0].RateDate != nil || rates.FetchedAt != nil || len(rates.MissingCurrencies) != 10 {
		t.Fatal("unavailable rates were fabricated")
	}
	// A partial fresh response replaces the whole old snapshot rather than mixing.
	upstream.clock.Advance(time.Minute)
	upstream.fail.Store(false)
	upstream.body.Store(fmt.Sprintf(`[{"base":"USD","quote":"EUR","date":"%s","rate":0.9}]`, upstream.clock.Now().Format(time.DateOnly)))
	w = request(f.router, "GET", "/api/v1/exchange-rates?base=USD", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	rates = decodeResponse[service.ExchangeRatesView](t, w)
	if len(rates.Rates) != 2 || rates.Stale || rates.FetchedAt == nil || len(rates.MissingCurrencies) != 9 {
		t.Fatal("partial new snapshot mixed in old rates")
	}
}

func TestIntegrationFXIdentityBypassOldObservationsAndRoundedAccountSums(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	upstream.fail.Store(true)
	f = withFX(t, f, upstream)
	user := registerUser(t, f)
	a := fxAccount(t, f, user.AccessToken, "KZT", "-15")
	w := request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", map[string]string{"from_currency": "KWD", "to_currency": "KWD", "amount_minor": "-15"})
	expectStatus(t, w, 200)
	conversion := decodeResponse[service.ConversionView](t, w)
	if conversion.ConvertedAmountMinor != "-15" || conversion.Rate != "1" || conversion.FetchedAt != nil || conversion.RateDate != nil || conversion.Stale {
		t.Fatal("identity conversion metadata wrong")
	}
	w = request(f.router, "GET", "/api/v1/portfolio?base=KZT", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	portfolio := decodeResponse[service.PortfolioView](t, w)
	if !portfolio.Complete || *portfolio.TotalBalanceMinor != "-15" || portfolio.FetchedAt != nil || upstream.calls.Load() != 0 {
		t.Fatal("all-identity portfolio made an upstream request")
	}
	// Old daily data stays explicitly missing even if fetched just now.
	upstream.fail.Store(false)
	upstream.body.Store(`[{"base":"USD","quote":"EUR","date":"2026-10-01","rate":0.5}]`)
	w = request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", map[string]string{"from_currency": "USD", "to_currency": "EUR", "amount_minor": "1"})
	expectStatus(t, w, 503)
	upstream.clock.Advance(7 * time.Hour)
	upstream.body.Store(`[{"base":"USD","quote":"EUR","date":"2026-10-11","rate":0.5}]`)
	fxAccount(t, f, user.AccessToken, "USD", "1")
	fxAccount(t, f, user.AccessToken, "USD", "1")
	w = request(f.router, "GET", "/api/v1/portfolio?base=EUR", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	portfolio = decodeResponse[service.PortfolioView](t, w)
	// Two half-cent conversions each round to the even zero, not one combined cent.
	if portfolio.Complete || portfolio.PartialBalanceMinor != "0" || len(portfolio.MissingCurrencies) != 1 || portfolio.MissingCurrencies[0] != "KZT" {
		t.Fatal("portfolio did not sum individually rounded account values")
	}
	for _, item := range portfolio.Accounts {
		if item.ID == a.ID && item.ConvertedBalanceMinor != nil {
			t.Fatal("missing KZT rate unexpectedly converted")
		}
	}
}

func TestIntegrationFXConversionAndAggregateOverflow(t *testing.T) {
	f := integrationFixture(t)
	upstream := newFakeFrankfurter(t)
	upstream.body.Store(`[{"base":"USD","quote":"KWD","date":"2026-10-11","rate":1000}]`)
	f = withFX(t, f, upstream)
	user := registerUser(t, f)
	w := request(f.router, "POST", "/api/v1/convert", user.AccessToken, "", map[string]string{"from_currency": "USD", "to_currency": "KWD", "amount_minor": "1000000000000000"})
	expectStatus(t, w, 409)
	// A portfolio total can overflow even if each account's native balance fits.
	a := fxAccount(t, f, user.AccessToken, "USD", "0")
	whole := int64(math.MaxInt64) / service.MaxMinor
	remainder := int64(math.MaxInt64) % service.MaxMinor
	if err := f.db.Exec(`INSERT INTO transactions(id,user_id,account_id,kind,amount_minor,currency,note,occurred_at,created_at)
		SELECT gen_random_uuid(), ?::uuid, ?::uuid, 'income', ?, 'USD', '', now(), now() FROM generate_series(1, ?)`, user.User.ID, a.ID, service.MaxMinor, whole).Error; err != nil {
		t.Fatal(err)
	}
	if err := f.db.Create(&model.Transaction{ID: uuid.NewString(), UserID: user.User.ID, AccountID: a.ID, Kind: "income", AmountMinor: remainder, Currency: "USD", OccurredAt: time.Now(), CreatedAt: time.Now()}).Error; err != nil {
		t.Fatal(err)
	}
	fxAccount(t, f, user.AccessToken, "USD", "1")
	expectStatus(t, request(f.router, "GET", "/api/v1/portfolio?base=USD", user.AccessToken, "", nil), 409)
	// Single-account balances above2^53 remain exact, including an identity row.
	if err := f.db.Exec("DELETE FROM accounts WHERE user_id = ? AND id <> ?", user.User.ID, a.ID).Error; err != nil {
		t.Fatal(err)
	}
	w = request(f.router, "GET", "/api/v1/portfolio?base=USD", user.AccessToken, "", nil)
	expectStatus(t, w, 200)
	portfolio := decodeResponse[service.PortfolioView](t, w)
	if !portfolio.Complete || portfolio.TotalBalanceMinor == nil || *portfolio.TotalBalanceMinor != "9223372036854775807" || portfolio.Accounts[0].ConvertedBalanceMinor == nil || *portfolio.Accounts[0].ConvertedBalanceMinor != "9223372036854775807" {
		t.Fatal("large exact portfolio balance changed")
	}
}
