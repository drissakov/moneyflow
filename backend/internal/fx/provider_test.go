package fx

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestFrankfurterV2QueryAndExactDecimals(t *testing.T) {
	now := time.Date(2026, 10, 11, 12, 0, 0, 0, time.UTC)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v2/rates" || r.URL.Query().Get("base") != "USD" || r.URL.Query().Get("quotes") != strings.Join(Codes(), ",") || len(r.URL.Query()) != 2 {
			t.Error("upstream request contains unexpected parameters")
		}
		if r.Header.Get("Authorization") != "" || r.Method != "GET" {
			t.Error("private data sent to rate provider")
		}
		fmt.Fprint(w, `[{"base":"USD","quote":"KZT","date":"2026-10-09","rate":450.12345678901234567890123456789},{"base":"USD","quote":"KWD","date":"2026-10-10","rate":3.05e-1}]`)
	}))
	defer server.Close()
	provider, err := NewFrankfurter(server.URL + "/v2")
	if err != nil {
		t.Fatal(err)
	}
	provider.Now = func() time.Time { return now }
	snapshot, err := provider.Fetch(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.Rates["KZT"].Rate != "450.12345678901234567890123456789" || snapshot.Rates["KWD"].Rate != "0.305" || snapshot.Rates["KZT"].Date != "2026-10-09" {
		t.Fatal("upstream decimals or dates changed")
	}
}

func TestFrankfurterRejectsMalformedResponsesAndTimeout(t *testing.T) {
	valid := `{"base":"USD","quote":"EUR","date":"2026-10-11","rate":0.9}`
	for _, body := range []string{
		`[]`, `null`, `{}`, `not-json`, `[` + valid + `,` + valid + `]`,
		`[{"base":"EUR","quote":"KZT","date":"2026-10-11","rate":450}]`,
		`[{"base":"USD","quote":"BTC","date":"2026-10-11","rate":1}]`,
		`[{"base":"USD","quote":"EUR","date":"2026-10-12","rate":1}]`,
		`[{"base":"USD","quote":"EUR","date":"not-a-date","rate":1}]`,
		`[{"base":"USD","quote":"EUR","date":"2026-10-11","rate":0}]`,
		`[{"base":"USD","quote":"EUR","date":"2026-10-11","rate":-1}]`,
		`[{"base":"USD","quote":"EUR","date":"2026-10-11","rate":"1"}]`,
		`[{"base":"USD","quote":"USD","date":"2026-10-11","rate":2}]`,
		strings.Repeat("x", 65537),
	} {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { fmt.Fprint(w, body) }))
		provider, _ := NewFrankfurter(server.URL)
		provider.Now = func() time.Time { return time.Date(2026, 10, 11, 12, 0, 0, 0, time.UTC) }
		_, err := provider.Fetch(context.Background())
		server.Close()
		if err == nil {
			t.Error("malformed upstream response accepted")
		}
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(50 * time.Millisecond)
		fmt.Fprint(w, `[`+valid+`]`)
	}))
	defer server.Close()
	provider, _ := NewFrankfurter(server.URL)
	provider.Client.Timeout = 10 * time.Millisecond
	if _, err := provider.Fetch(context.Background()); err == nil {
		t.Fatal("upstream timeout not enforced")
	}
}

func TestFrankfurterURLValidation(t *testing.T) {
	for _, raw := range []string{"https://api.frankfurter.dev/v2", "http://localhost:8081/v2", "http://127.0.0.1:8081/v2", "http://[::1]:8081/v2"} {
		if err := ValidateURL(raw); err != nil {
			t.Errorf("valid URL rejected:%s", raw)
		}
	}
	for _, raw := range []string{"http://api.frankfurter.dev/v2", "https://user:password@example.com", "https://example.com?token=x", "https://example.com#fragment", "file:///tmp/rates"} {
		if err := ValidateURL(raw); err == nil {
			t.Error("unsafe URL accepted")
		}
	}
}
