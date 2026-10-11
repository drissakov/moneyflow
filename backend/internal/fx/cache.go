package fx

import (
	"context"
	"encoding/json"
	"errors"
	"math/big"
	"strings"
	"sync"
	"time"

	"gorm.io/gorm"
)

func newOne() *big.Rat { return big.NewRat(1, 1) }

var errRefreshUnavailable = errors.New("rates refresh unavailable")

type Cache struct {
	db       *gorm.DB
	provider Provider
	ttl      time.Duration
	maxStale time.Duration
	now      func() time.Time
	mu       sync.Mutex
	inflight chan struct{}
	retryAt  time.Time
}

func NewCache(db *gorm.DB, provider Provider, ttl, maxStale time.Duration, now func() time.Time) *Cache {
	if now == nil {
		now = time.Now
	}
	return &Cache{db: db, provider: provider, ttl: ttl, maxStale: maxStale, now: now}
}

func (c *Cache) read(ctx context.Context) (*Snapshot, error) {
	var row struct {
		Rates     string
		FetchedAt time.Time
	}
	result := c.db.WithContext(ctx).Raw("SELECT rates::text, fetched_at FROM fx_snapshots WHERE anchor = 'USD'").Scan(&row)
	if result.Error != nil {
		return nil, result.Error
	}
	if row.FetchedAt.IsZero() {
		return nil, nil
	}
	snapshot := &Snapshot{FetchedAt: row.FetchedAt, Rates: make(map[string]Quote)}
	if err := json.Unmarshal([]byte(row.Rates), &snapshot.Rates); err != nil {
		return nil, nil
	}
	if !validateSnapshot(snapshot, c.now()) {
		return nil, nil
	}
	return snapshot, nil
}

func validateSnapshot(snapshot *Snapshot, now time.Time) bool {
	if snapshot == nil || snapshot.FetchedAt.IsZero() || snapshot.FetchedAt.After(now.Add(time.Minute)) || len(snapshot.Rates) > len(catalogue) {
		return false
	}
	for code, quote := range snapshot.Rates {
		if _, ok := Lookup(code); !ok {
			return false
		}
		if len(quote.Rate) > 256 || strings.ContainsAny(quote.Rate, "eE") {
			return false
		}
		rate, err := ParseRate(quote.Rate)
		if err != nil || code == "USD" && rate.Cmp(newOne()) != 0 {
			return false
		}
		bound := new(big.Int).Exp(big.NewInt(10), big.NewInt(228), nil)
		if rate.Cmp(new(big.Rat).SetInt(bound)) > 0 || rate.Cmp(new(big.Rat).SetFrac(big.NewInt(1), bound)) < 0 {
			return false
		}
		date, err := time.Parse(time.DateOnly, quote.Date)
		if err != nil || date.Year() < 1 || date.Format(time.DateOnly) != quote.Date || date.After(day(now)) {
			return false
		}
	}
	return true
}

func (c *Cache) usable(snapshot *Snapshot) (*Snapshot, bool) {
	if snapshot == nil || c.now().Sub(snapshot.FetchedAt) > c.maxStale {
		return nil, false
	}
	return snapshot, c.now().Sub(snapshot.FetchedAt) > c.ttl
}

// Get deduplicates overlapping refreshes. Successful responses replace the whole
// snapshot; missing quotes are never silently filled with an older snapshot.
// A short retry cooldown also prevents repeatedly hammering an unavailable API.
func (c *Cache) Get(ctx context.Context) (*Snapshot, bool, error) {
	previous, err := c.read(ctx)
	if err != nil {
		return nil, false, err
	}
	if previous != nil && c.now().Sub(previous.FetchedAt) <= c.ttl {
		return previous, false, nil
	}
	if c.provider == nil {
		snapshot, stale := c.usable(previous)
		return snapshot, stale, nil
	}
	c.mu.Lock()
	if c.inflight != nil {
		wait := c.inflight
		c.mu.Unlock()
		select {
		case <-ctx.Done():
			return nil, false, ctx.Err()
		case <-wait:
		}
		snapshot, err := c.read(ctx)
		if err != nil {
			return nil, false, err
		}
		snapshot, stale := c.usable(snapshot)
		return snapshot, stale, nil
	}
	if c.retryAt.After(c.now()) {
		c.mu.Unlock()
		latest, err := c.read(ctx)
		if err != nil {
			return nil, false, err
		}
		snapshot, stale := c.usable(latest)
		return snapshot, stale, nil
	}
	c.inflight = make(chan struct{})
	wait := c.inflight
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.retryAt = c.now().Add(30 * time.Second)
		c.inflight = nil
		close(wait)
		c.mu.Unlock()
	}()
	// A transaction-scoped database lock deduplicates across API instances too.
	var snapshot *Snapshot
	err = c.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Exec("SELECT pg_advisory_xact_lock(625648446712)").Error; err != nil {
			return err
		}
		// Another process may have refreshed while this process waited for the lock.
		var row struct {
			Rates     string
			FetchedAt time.Time
		}
		if err := tx.Raw("SELECT rates::text, fetched_at FROM fx_snapshots WHERE anchor = 'USD'").Scan(&row).Error; err != nil {
			return err
		}
		if !row.FetchedAt.IsZero() && c.now().Sub(row.FetchedAt) <= c.ttl {
			cached := &Snapshot{FetchedAt: row.FetchedAt, Rates: make(map[string]Quote)}
			if json.Unmarshal([]byte(row.Rates), &cached.Rates) == nil && validateSnapshot(cached, c.now()) {
				snapshot = cached
				return nil
			}
		}
		refresh, fetchErr := c.provider.Fetch(ctx)
		if fetchErr != nil || !validateSnapshot(refresh, c.now()) {
			return errRefreshUnavailable
		}
		body, marshalErr := json.Marshal(refresh.Rates)
		if marshalErr != nil {
			return marshalErr
		}
		if err := tx.Exec(`INSERT INTO fx_snapshots(anchor, rates, fetched_at) VALUES ('USD', ?::jsonb, ?)
			ON CONFLICT(anchor) DO UPDATE SET rates = EXCLUDED.rates, fetched_at = EXCLUDED.fetched_at`, string(body), refresh.FetchedAt).Error; err != nil {
			return err
		}
		snapshot = refresh
		return nil
	})
	if err != nil {
		if ctx.Err() != nil {
			return nil, false, ctx.Err()
		}
		if !errors.Is(err, errRefreshUnavailable) {
			return nil, false, err
		}
		snapshot, stale := c.usable(previous)
		return snapshot, stale, nil
	}
	snapshot, stale := c.usable(snapshot)
	return snapshot, stale, nil
}

// Pair derives one cross rate from the same anchor snapshot. USD=1 is the
// mathematical anchor identity, never a substitute for a missing foreign quote.
func (c *Cache) Pair(snapshot *Snapshot, from, to string) (*big.Rat, *string, bool) {
	if from == to {
		return newOne(), nil, true
	}
	if snapshot == nil {
		return nil, nil, false
	}
	read := func(code string) (*big.Rat, string, bool) {
		if code == "USD" {
			return newOne(), "", true
		}
		quote, ok := snapshot.Rates[code]
		if !ok {
			return nil, "", false
		}
		date, err := time.Parse(time.DateOnly, quote.Date)
		if err != nil || day(c.now()).Sub(date) > c.maxStale {
			return nil, "", false
		}
		rate, err := ParseRate(quote.Rate)
		return rate, quote.Date, err == nil
	}
	source, sourceDate, sourceOK := read(from)
	target, targetDate, targetOK := read(to)
	if !sourceOK || !targetOK {
		return nil, nil, false
	}
	date := targetDate
	if date == "" || sourceDate != "" && sourceDate < date {
		date = sourceDate
	}
	return new(big.Rat).Quo(target, source), &date, true
}
