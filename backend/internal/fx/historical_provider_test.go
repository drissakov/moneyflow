package fx

import (
	"context"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func historyDate(raw string) time.Time {
	date, err := time.Parse(time.DateOnly, raw)
	if err != nil {
		panic(err)
	}
	return date
}

func TestHistoricalProviderExactQueryObservationsAndCalendar(t *testing.T) {
	now := time.Date(2026, 10, 12, 20, 30, 0, 0, time.UTC) // Already October 13 in KZ.
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		if r.URL.Path != "/v2/rates" || len(q) != 4 || q.Get("base") != "USD" || q.Get("quotes") != strings.Join(Codes(), ",") || q.Get("from") != "2026-10-10" || q.Get("to") != "2026-10-13" || r.Header.Get("Authorization") != "" || r.Method != http.MethodGet {
			t.Error("historical provider received unexpected query or private information")
		}
		fmt.Fprint(w, `[{"base":"USD","quote":"KZT","date":"2026-10-11","rate":450.12345678901234567890123456789},{"base":"USD","quote":"KWD","date":"2026-10-09","rate":3.05e-1},{"base":"USD","quote":"KZT","date":"2026-10-13","rate":460},{"base":"USD","quote":"KZT","date":"2026-10-10","rate":449}]`)
	}))
	defer server.Close()
	provider, _ := NewFrankfurter(server.URL + "/v2")
	provider.Now = func() time.Time { return now }
	from := time.Date(2026, 10, 10, 0, 0, 0, 0, time.FixedZone("KZ", 5*60*60))
	result, err := provider.FetchRange(context.Background(), from, historyDate("2026-10-13"))
	if err != nil {
		t.Fatal(err)
	}
	if result.FromDate != "2026-10-10" || result.ToDate != "2026-10-13" || result.Quotes["KWD"][0].Rate != "0.305" || result.Quotes["KZT"][1].Date != "2026-10-11" || result.Quotes["KZT"][1].Rate != "450.12345678901234567890123456789" {
		t.Fatal("historical decimal, weekend observation, or calendar date changed")
	}
}

func TestHistoricalProviderRejectsBadAndOversizedResponses(t *testing.T) {
	valid := `{"base":"USD","quote":"KZT","date":"2026-10-10","rate":450}`
	for _, body := range []string{
		`null`, `{}`, `broken`, `[` + valid + `,` + valid + `]`,
		`[{"base":"EUR","quote":"KZT","date":"2026-10-10","rate":450}]`,
		`[{"base":"USD","quote":"BTC","date":"2026-10-10","rate":450}]`,
		`[{"base":"USD","quote":"KZT","date":"2026-10-12","rate":450}]`,
		`[{"base":"USD","quote":"KZT","date":"2026-09-25","rate":450}]`,
		`[{"base":"USD","quote":"KZT","date":"2026-10-10","rate":"450"}]`,
		`[{"base":"USD","quote":"KZT","date":"2026-10-10","rate":0}]`,
		`[{"base":"USD","quote":"KZT","date":"2026-10-10","rate":-1}]`,
		`[{"base":"USD","quote":"KZT","date":"2026-10-10","rate":1e101}]`,
		`[{"base":"USD","quote":"USD","date":"2026-10-10","rate":2}]`,
		`[` + strings.Repeat(valid+",", maxHistoricalRows) + valid + `]`,
		strings.Repeat(" ", maxHistoricalResponse+1),
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { fmt.Fprint(w, body) }))
		provider, _ := NewFrankfurter(server.URL)
		provider.Now = func() time.Time { return historyDate("2026-10-11") }
		_, err := provider.FetchRange(context.Background(), historyDate("2026-10-10"), historyDate("2026-10-11"))
		server.Close()
		if err == nil {
			t.Fatal("malformed historical response accepted")
		}
	}
}

func TestHistoricalProviderEmptyHistoryTimeoutAndRedirect(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/empty/rates":
			fmt.Fprint(w, `[]`)
		case "/slow/rates":
			time.Sleep(40 * time.Millisecond)
			fmt.Fprint(w, `[]`)
		default:
			http.Redirect(w, r, "/empty/rates", http.StatusFound)
		}
	}))
	defer server.Close()
	provider, _ := NewFrankfurter(server.URL + "/empty")
	provider.Now = func() time.Time { return historyDate("2026-10-11") }
	if result, err := provider.FetchRange(context.Background(), historyDate("1900-01-01"), historyDate("1900-12-31")); err != nil || len(result.Quotes) != 0 {
		t.Fatal("valid empty historical coverage rejected")
	}
	provider.URL = server.URL + "/slow"
	provider.Client.Timeout = 5 * time.Millisecond
	if _, err := provider.FetchRange(context.Background(), historyDate("2026-10-10"), historyDate("2026-10-11")); err == nil {
		t.Fatal("historical provider timeout not enforced")
	}
	provider.URL = server.URL + "/redirect"
	if _, err := provider.FetchRange(context.Background(), historyDate("2026-10-10"), historyDate("2026-10-11")); err == nil {
		t.Fatal("historical provider followed redirect")
	}
}

func TestHistoricalSeriesWeekendAsOfCrossRatesAndCarryLimit(t *testing.T) {
	series := &HistoricalSeries{quotes: map[string][]Quote{
		"KZT": {{Date: "2026-10-09", Rate: "450"}, {Date: "2026-10-11", Rate: "455"}, {Date: "2026-10-12", Rate: "460"}},
		"EUR": {{Date: "2026-10-09", Rate: "0.8"}},
	}}
	rate, sourceDate, targetDate, ok := series.Pair("EUR", "KZT", historyDate("2026-10-11"))
	if !ok || rate.Cmp(big.NewRat(2275, 4)) != 0 || *sourceDate != "2026-10-09" || *targetDate != "2026-10-11" {
		t.Fatal("weekend quote skipped or later quote leaked into historical cross")
	}
	if rate, _, date, ok := series.Pair("USD", "KZT", historyDate("2026-10-10")); !ok || rate.Cmp(big.NewRat(450, 1)) != 0 || *date != "2026-10-09" {
		t.Fatal("silent day did not carry its most recent observation")
	}
	if _, _, _, ok := series.Pair("USD", "KZT", historyDate("2026-10-08")); ok {
		t.Fatal("observation from future used")
	}
	if _, _, _, ok := series.Pair("EUR", "KZT", historyDate("2026-10-23")); !ok {
		t.Fatal("fourteen day boundary rejected")
	}
	if _, _, _, ok := series.Pair("EUR", "KZT", historyDate("2026-10-24")); ok {
		t.Fatal("expired historical carry accepted")
	}
	if _, _, _, ok := series.Pair("JPY", "KZT", historyDate("2026-10-11")); ok {
		t.Fatal("missing foreign quote replaced by another currency")
	}
	var absent *HistoricalSeries
	if rate, source, target, ok := absent.Pair("KZT", "KZT", time.Time{}); !ok || rate.Cmp(newOne()) != 0 || source != nil || target != nil {
		t.Fatal("same-currency identity requires a provider")
	}
}
