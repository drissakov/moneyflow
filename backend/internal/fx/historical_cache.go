package fx

import (
	"context"
	"encoding/json"
	"errors"
	"math/big"
	"sort"
	"strings"
	"sync"
	"time"

	"gorm.io/gorm"
)

const historicalOpenTTL = 6 * time.Hour
const historicalClosedTTL = 30 * 24 * time.Hour

type historicalYearFlight struct {
	done    chan struct{}
	retryAt time.Time
}

type HistoricalCache struct {
	db       *gorm.DB
	provider HistoricalProvider
	now      func() time.Time
	mu       sync.Mutex
	flights  map[int]*historicalYearFlight
}

func NewHistoricalCache(db *gorm.DB, provider HistoricalProvider, now func() time.Time) *HistoricalCache {
	if now == nil {
		now = time.Now
	}
	return &HistoricalCache{db: db, provider: provider, now: now, flights: make(map[int]*historicalYearFlight)}
}

func (c *HistoricalCache) read(ctx context.Context, db *gorm.DB, year int) (*HistoricalSnapshot, error) {
	var row struct {
		CoveredFrom string
		CoveredTo   string
		Rates       string
		FetchedAt   time.Time
	}
	if err := db.WithContext(ctx).Raw(`SELECT covered_from::text,covered_to::text,rates::text,fetched_at FROM fx_historical_snapshots WHERE year=?`, year).Scan(&row).Error; err != nil {
		return nil, err
	}
	if row.FetchedAt.IsZero() || len(row.Rates) > maxHistoricalResponse {
		return nil, nil
	}
	snapshot := &HistoricalSnapshot{FetchedAt: row.FetchedAt, FromDate: row.CoveredFrom, ToDate: row.CoveredTo}
	if json.Unmarshal([]byte(row.Rates), &snapshot.Quotes) != nil || !validateHistoricalSnapshot(snapshot, year, c.now()) {
		return nil, nil
	}
	return snapshot, nil
}

func validateHistoricalSnapshot(snapshot *HistoricalSnapshot, year int, now time.Time) bool {
	if snapshot == nil || snapshot.FetchedAt.IsZero() || snapshot.FetchedAt.After(now.Add(time.Minute)) || snapshot.Quotes == nil || len(snapshot.Quotes) > len(catalogue) {
		return false
	}
	from := time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC).AddDate(0, 0, -MaxPreviousDays)
	to, err := time.Parse(time.DateOnly, snapshot.ToDate)
	if err != nil || snapshot.FromDate != from.Format(time.DateOnly) || to.Year() != year || to.Format(time.DateOnly) != snapshot.ToDate || to.After(historicalToday(snapshot.FetchedAt)) || to.After(historicalToday(now)) {
		return false
	}
	count := 0
	bound := new(big.Int).Exp(big.NewInt(10), big.NewInt(228), nil)
	for currency, quotes := range snapshot.Quotes {
		if _, ok := Lookup(currency); !ok {
			return false
		}
		previous := ""
		for _, quote := range quotes {
			count++
			if count > maxHistoricalRows || quote.Date <= previous || len(quote.Rate) > 256 || strings.ContainsAny(quote.Rate, "eE") {
				return false
			}
			date, err := time.Parse(time.DateOnly, quote.Date)
			if err != nil || date.Year() < 1 || date.Format(time.DateOnly) != quote.Date || date.Before(from.AddDate(0, 0, -MaxPreviousDays)) || date.After(to) {
				return false
			}
			rate, err := ParseRate(quote.Rate)
			if err != nil || currency == "USD" && rate.Cmp(newOne()) != 0 || rate.Cmp(new(big.Rat).SetInt(bound)) > 0 || rate.Cmp(new(big.Rat).SetFrac(big.NewInt(1), bound)) < 0 {
				return false
			}
			previous = quote.Date
		}
	}
	return true
}

func (c *HistoricalCache) covered(snapshot *HistoricalSnapshot, neededTo time.Time) bool {
	return snapshot != nil && snapshot.ToDate >= neededTo.Format(time.DateOnly)
}

func (c *HistoricalCache) fresh(snapshot *HistoricalSnapshot, year int) bool {
	if snapshot == nil {
		return false
	}
	ttl := historicalClosedTTL
	if year == historicalToday(c.now()).Year() {
		ttl = historicalOpenTTL
	}
	return c.now().Sub(snapshot.FetchedAt) <= ttl
}

func (c *HistoricalCache) year(ctx context.Context, year int, neededTo time.Time) (*HistoricalSnapshot, bool, error) {
	previous, err := c.read(ctx, c.db, year)
	if err != nil {
		return nil, false, err
	}
	if c.covered(previous, neededTo) && c.fresh(previous, year) {
		return previous, false, nil
	}
	fallback := func(snapshot *HistoricalSnapshot) (*HistoricalSnapshot, bool, error) {
		if c.covered(snapshot, neededTo) {
			return snapshot, true, nil
		}
		return nil, false, ErrHistoricalUnavailable
	}
	if c.provider == nil {
		return fallback(previous)
	}
	c.mu.Lock()
	flight := c.flights[year]
	if flight != nil && flight.done != nil {
		wait := flight.done
		c.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, false, ctx.Err()
		case <-wait:
		}
		latest, err := c.read(ctx, c.db, year)
		if err != nil {
			return nil, false, err
		}
		if c.covered(latest, neededTo) && c.fresh(latest, year) {
			return latest, false, nil
		}
		return fallback(latest)
	}
	if flight != nil && flight.retryAt.After(c.now()) {
		c.mu.Unlock()
		return fallback(previous)
	}
	wait := make(chan struct{})
	c.flights[year] = &historicalYearFlight{done: wait}
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.flights[year] = &historicalYearFlight{retryAt: c.now().Add(30 * time.Second)}
		close(wait)
		c.mu.Unlock()
	}()
	var refreshed *HistoricalSnapshot
	err = c.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		// One lock per public calendar-year cache, shared by all API instances.
		if err := tx.Exec("SELECT pg_advisory_xact_lock(?)", int64(625648447000)+int64(year)).Error; err != nil {
			return err
		}
		latest, err := c.read(ctx, tx, year)
		if err != nil {
			return err
		}
		if c.covered(latest, neededTo) && c.fresh(latest, year) {
			refreshed = latest
			return nil
		}
		from := time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC).AddDate(0, 0, -MaxPreviousDays)
		to := time.Date(year, 12, 31, 0, 0, 0, 0, time.UTC)
		if today := historicalToday(c.now()); to.After(today) {
			to = today
		}
		candidate, fetchErr := c.provider.FetchRange(ctx, from, to)
		if fetchErr != nil || !validateHistoricalSnapshot(candidate, year, c.now()) || candidate.FromDate != from.Format(time.DateOnly) || candidate.ToDate != to.Format(time.DateOnly) {
			return errRefreshUnavailable
		}
		body, err := json.Marshal(candidate.Quotes)
		if err != nil || len(body) > maxHistoricalResponse {
			return errRefreshUnavailable
		}
		if err := tx.Exec(`INSERT INTO fx_historical_snapshots(year,covered_from,covered_to,rates,fetched_at) VALUES(?,?::date,?::date,?::jsonb,?)
ON CONFLICT(year) DO UPDATE SET covered_from=EXCLUDED.covered_from,covered_to=EXCLUDED.covered_to,rates=EXCLUDED.rates,fetched_at=EXCLUDED.fetched_at`, year, candidate.FromDate, candidate.ToDate, string(body), candidate.FetchedAt).Error; err != nil {
			return err
		}
		refreshed = candidate
		return nil
	})
	if ctx.Err() != nil {
		return nil, false, ctx.Err()
	}
	if err != nil {
		if errors.Is(err, errRefreshUnavailable) {
			return fallback(previous)
		}
		return nil, false, err
	}
	return refreshed, !c.fresh(refreshed, year), nil
}

// Get assembles at most two adjacent years, enough for twelve monthly points
// and their opening balance. Successful refreshes replace whole year books;
// missing currencies are never stitched in from an older response.
func (c *HistoricalCache) Get(ctx context.Context, from, to time.Time) (*HistoricalSeries, bool, error) {
	from, to = historicalDay(from), historicalDay(to)
	if from.Year() < 1900 || to.Year() > 2199 || to.Before(from) || to.Year()-from.Year() > 1 || to.After(historicalToday(c.now())) {
		return nil, false, ErrHistoricalUnavailable
	}
	series := &HistoricalSeries{quotes: make(map[string][]Quote)}
	type observation struct {
		quote Quote
		fetch time.Time
		year  int
	}
	book := make(map[string]map[string]observation)
	stale := false
	for year := from.Year(); year <= to.Year(); year++ {
		neededTo := time.Date(year, 12, 31, 0, 0, 0, 0, time.UTC)
		if to.Before(neededTo) {
			neededTo = to
		}
		snapshot, yearStale, err := c.year(ctx, year, neededTo)
		if err != nil {
			return nil, false, err
		}
		stale = stale || yearStale
		if series.FetchedAt.IsZero() || snapshot.FetchedAt.Before(series.FetchedAt) {
			series.FetchedAt = snapshot.FetchedAt
		}
		for code, quotes := range snapshot.Quotes {
			if book[code] == nil {
				book[code] = make(map[string]observation)
			}
			for _, quote := range quotes {
				old, exists := book[code][quote.Date]
				if !exists || snapshot.FetchedAt.After(old.fetch) || snapshot.FetchedAt.Equal(old.fetch) && year > old.year {
					book[code][quote.Date] = observation{quote, snapshot.FetchedAt, year}
				}
			}
		}
	}
	for code, observations := range book {
		for _, item := range observations {
			series.quotes[code] = append(series.quotes[code], item.quote)
		}
		sort.Slice(series.quotes[code], func(i, j int) bool { return series.quotes[code][i].Date < series.quotes[code][j].Date })
	}
	return series, stale, nil
}
