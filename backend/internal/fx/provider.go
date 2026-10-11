package fx

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Quote struct {
	Rate string `json:"rate"`
	Date string `json:"date"`
}

type Snapshot struct {
	FetchedAt time.Time
	Rates     map[string]Quote
}

type Provider interface {
	Fetch(context.Context) (*Snapshot, error)
}

type Frankfurter struct {
	URL    string
	Client *http.Client
	Now    func() time.Time
}

func ValidateURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || u.Opaque != "" {
		return errors.New("FRANKFURTER_URL must be a plain HTTPS base URL")
	}
	if u.Scheme == "https" {
		return nil
	}
	ip := net.ParseIP(u.Hostname())
	if u.Scheme == "http" && (u.Hostname() == "localhost" || ip != nil && ip.IsLoopback()) {
		return nil
	}
	return errors.New("FRANKFURTER_URL requires HTTPS except for loopback test fixtures")
}

func NewFrankfurter(baseURL string) (*Frankfurter, error) {
	if err := ValidateURL(baseURL); err != nil {
		return nil, err
	}
	return &Frankfurter{URL: strings.TrimRight(baseURL, "/"), Now: time.Now, Client: &http.Client{
		Timeout: 5 * time.Second,
		// Redirects must not turn a trusted HTTPS upstream into another host or HTTP.
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
	}}, nil
}

func (f *Frankfurter) Fetch(ctx context.Context) (*Snapshot, error) {
	u, err := url.Parse(f.URL + "/rates")
	if err != nil {
		return nil, errors.New("invalid rates URL")
	}
	query := u.Query()
	query.Set("base", "USD")
	query.Set("quotes", strings.Join(Codes(), ","))
	u.RawQuery = query.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	response, err := f.Client.Do(req)
	if err != nil {
		return nil, errors.New("rates provider request failed")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, errors.New("rates provider unavailable")
	}
	const maxResponse = 64 * 1024
	data, err := io.ReadAll(io.LimitReader(response.Body, maxResponse+1))
	if err != nil || len(data) > maxResponse {
		return nil, errors.New("rates provider response exceeds limit")
	}
	var rows []struct {
		Date  string          `json:"date"`
		Base  string          `json:"base"`
		Quote string          `json:"quote"`
		Rate  json.RawMessage `json:"rate"`
	}
	if err := json.Unmarshal(data, &rows); err != nil || len(rows) == 0 || len(rows) > len(catalogue) {
		return nil, errors.New("invalid rates provider response")
	}
	result := &Snapshot{FetchedAt: f.Now().UTC().Truncate(time.Microsecond), Rates: make(map[string]Quote)}
	for _, row := range rows {
		if row.Base != "USD" {
			return nil, errors.New("rates provider returned wrong anchor")
		}
		if _, ok := Lookup(row.Quote); !ok {
			return nil, errors.New("rates provider returned unexpected currency")
		}
		if _, exists := result.Rates[row.Quote]; exists {
			return nil, errors.New("rates provider returned duplicate currency")
		}
		if len(row.Rate) > 128 {
			return nil, errors.New("rates provider returned oversized rate")
		}
		rate, err := ParseRate(string(row.Rate))
		if err != nil || row.Quote == "USD" && rate.Cmp(newOne()) != 0 {
			return nil, errors.New("rates provider returned invalid rate")
		}
		date, err := time.Parse(time.DateOnly, row.Date)
		if err != nil || date.Year() < 1 || date.Format(time.DateOnly) != row.Date || date.After(day(result.FetchedAt)) {
			return nil, errors.New("rates provider returned invalid date")
		}
		result.Rates[row.Quote] = Quote{Rate: ExactDecimalString(rate), Date: row.Date}
	}
	return result, nil
}

func day(value time.Time) time.Time {
	value = value.UTC()
	return time.Date(value.Year(), value.Month(), value.Day(), 0, 0, 0, 0, time.UTC)
}
