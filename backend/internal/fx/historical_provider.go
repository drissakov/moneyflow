package fx

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

const maxHistoricalResponse = 2 * 1024 * 1024
const maxHistoricalRows = 5000

// FetchRange requests only public dates and currencies; no ledger or identity
// data is sent to Frankfurter. Monthly grouping would return averages and must
// never be used for month-end valuations.
func (f *Frankfurter) FetchRange(ctx context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
	from, to = historicalDay(from), historicalDay(to)
	if from.Year() < 1 || to.Before(from) || to.Sub(from) > 400*24*time.Hour || to.After(historicalToday(f.Now())) {
		return nil, errors.New("invalid historical rates range")
	}
	u, err := url.Parse(f.URL + "/rates")
	if err != nil {
		return nil, errors.New("invalid historical rates URL")
	}
	query := u.Query()
	query.Set("base", "USD")
	query.Set("quotes", strings.Join(Codes(), ","))
	query.Set("from", from.Format(time.DateOnly))
	query.Set("to", to.Format(time.DateOnly))
	u.RawQuery = query.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	response, err := f.Client.Do(req)
	if err != nil {
		return nil, ErrHistoricalUnavailable
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, ErrHistoricalUnavailable
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxHistoricalResponse+1))
	if err != nil || len(body) > maxHistoricalResponse {
		return nil, errors.New("historical rates response exceeds limit")
	}
	var rows []struct {
		Date  string          `json:"date"`
		Base  string          `json:"base"`
		Quote string          `json:"quote"`
		Rate  json.RawMessage `json:"rate"`
	}
	if json.Unmarshal(body, &rows) != nil || rows == nil || len(rows) > maxHistoricalRows {
		return nil, errors.New("invalid historical rates response")
	}
	result := &HistoricalSnapshot{FetchedAt: f.Now().UTC().Truncate(time.Microsecond), FromDate: from.Format(time.DateOnly), ToDate: to.Format(time.DateOnly), Quotes: make(map[string][]Quote)}
	seen := make(map[string]bool, len(rows))
	for _, row := range rows {
		if row.Base != "USD" || seen[row.Quote+":"+row.Date] || len(row.Rate) > 128 {
			return nil, errors.New("invalid historical rates row")
		}
		if _, ok := Lookup(row.Quote); !ok {
			return nil, errors.New("unexpected historical rates currency")
		}
		date, err := time.Parse(time.DateOnly, row.Date)
		if err != nil || date.Year() < 1 || date.Format(time.DateOnly) != row.Date || date.Before(from.AddDate(0, 0, -MaxPreviousDays)) || date.After(to) || date.After(historicalToday(result.FetchedAt)) {
			return nil, errors.New("invalid historical rate observation date")
		}
		rate, err := ParseRate(string(row.Rate))
		if err != nil || row.Quote == "USD" && rate.Cmp(newOne()) != 0 {
			return nil, errors.New("invalid historical rate value")
		}
		seen[row.Quote+":"+row.Date] = true
		result.Quotes[row.Quote] = append(result.Quotes[row.Quote], Quote{Rate: ExactDecimalString(rate), Date: row.Date})
	}
	for code := range result.Quotes {
		sort.Slice(result.Quotes[code], func(i, j int) bool { return result.Quotes[code][i].Date < result.Quotes[code][j].Date })
	}
	return result, nil
}
