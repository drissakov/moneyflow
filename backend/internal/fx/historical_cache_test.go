package fx

import (
	"context"
	"errors"
	"math/big"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/database"
	"github.com/drissakov/moneyflow/backend/internal/migrations"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type historicalProviderFunc func(context.Context, time.Time, time.Time) (*HistoricalSnapshot, error)

func (fn historicalProviderFunc) FetchRange(ctx context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
	return fn(ctx, from, to)
}

type historicalTestClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *historicalTestClock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.now }
func (c *historicalTestClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

func historicalFixture(t *testing.T) *gorm.DB {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("set TEST_DATABASE_URL for historical FX cache integration tests")
	}
	control, err := database.Open(dsn)
	if err != nil {
		t.Fatal("historical cache test database connection failed")
	}
	controlSQL, _ := control.DB()
	schema := "moneyflow_history_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	if err := control.Exec("CREATE SCHEMA " + schema).Error; err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { control.Exec("DROP SCHEMA " + schema + " CASCADE"); controlSQL.Close() })
	if strings.HasPrefix(dsn, "postgres://") || strings.HasPrefix(dsn, "postgresql://") {
		u, err := url.Parse(dsn)
		if err != nil {
			t.Fatal("invalid historical cache test database URL")
		}
		query := u.Query()
		query.Set("search_path", schema)
		u.RawQuery = query.Encode()
		dsn = u.String()
	} else {
		dsn += " search_path=" + schema
	}
	db, err := database.Open(dsn)
	if err != nil {
		t.Fatal("historical cache isolated database connection failed")
	}
	sqlDB, _ := db.DB()
	t.Cleanup(func() { sqlDB.Close() })
	if err := migrations.Apply(context.Background(), sqlDB); err != nil {
		t.Fatal(err)
	}
	return db
}

func testHistoricalSnapshot(from, to, fetched time.Time, includeEUR bool) *HistoricalSnapshot {
	result := &HistoricalSnapshot{FromDate: from.Format(time.DateOnly), ToDate: to.Format(time.DateOnly), FetchedAt: fetched, Quotes: map[string][]Quote{}}
	for date := from; !date.After(to); date = date.AddDate(0, 0, 1) {
		result.Quotes["KZT"] = append(result.Quotes["KZT"], Quote{Rate: "450", Date: date.Format(time.DateOnly)})
		if includeEUR {
			result.Quotes["EUR"] = append(result.Quotes["EUR"], Quote{Rate: "0.9", Date: date.Format(time.DateOnly)})
		}
	}
	return result
}

func TestIntegrationHistoricalCachePersistentYearsRefreshCoverageAndMissing(t *testing.T) {
	db := historicalFixture(t)
	clock := &historicalTestClock{now: time.Date(2026, 10, 12, 10, 0, 0, 0, time.UTC)}
	var calls atomic.Int64
	var fail, omitEUR atomic.Bool
	provider := historicalProviderFunc(func(_ context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
		calls.Add(1)
		if fail.Load() {
			return nil, ErrHistoricalUnavailable
		}
		if from.Format(time.DateOnly) != "2025-12-18" || to.Format(time.DateOnly) != historicalToday(clock.Now()).Format(time.DateOnly) {
			t.Error("cache did not request one bounded current-year book")
		}
		return testHistoricalSnapshot(from, to, clock.Now(), !omitEUR.Load()), nil
	})
	cache := NewHistoricalCache(db, provider, clock.Now)
	from, to := historyDate("2026-09-30"), historyDate("2026-10-11")
	series, stale, err := cache.Get(context.Background(), from, to)
	if err != nil || stale || calls.Load() != 1 {
		t.Fatal("cold historical year did not populate")
	}
	if rate, _, _, ok := series.Pair("EUR", "KZT", to); !ok || rate.Cmp(big.NewRat(500, 1)) != 0 {
		t.Fatal("cold historical series lacks exact cross")
	}
	// A new process reuses PostgreSQL, not a process-only book.
	if _, stale, err := NewHistoricalCache(db, provider, clock.Now).Get(context.Background(), from, to); err != nil || stale || calls.Load() != 1 {
		t.Fatal("persistent historical year was fetched twice")
	}
	clock.Advance(7 * time.Hour)
	fail.Store(true)
	if _, stale, err := cache.Get(context.Background(), from, to); err != nil || !stale || calls.Load() != 2 {
		t.Fatal("complete old historical year not retained as explicit stale fallback")
	}
	clock.Advance(24 * time.Hour)
	if _, _, err := cache.Get(context.Background(), from, historyDate("2026-10-13")); !errors.Is(err, ErrHistoricalUnavailable) {
		t.Fatal("incomplete coverage silently treated as complete")
	}
	clock.Advance(time.Minute)
	fail.Store(false)
	omitEUR.Store(true)
	series, stale, err = cache.Get(context.Background(), from, to)
	if err != nil || stale {
		t.Fatal("successful refresh did not replace old book")
	}
	if _, _, _, ok := series.Pair("EUR", "KZT", to); ok {
		t.Fatal("missing quote stitched from old snapshot")
	}
	if _, _, _, ok := series.Pair("USD", "KZT", historyDate("2026-10-13")); !ok {
		t.Fatal("new coverage was not persisted")
	}
}

func TestIntegrationHistoricalCacheClosedYearTTLAndOverlap(t *testing.T) {
	db := historicalFixture(t)
	clock := &historicalTestClock{now: time.Date(2026, 10, 12, 10, 0, 0, 0, time.UTC)}
	var calls atomic.Int64
	provider := historicalProviderFunc(func(_ context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
		calls.Add(1)
		snapshot := testHistoricalSnapshot(from, to, clock.Now(), true)
		if from.Year() == 2025 { // 2026 book's fourteen-day lookback revises December.
			for i := range snapshot.Quotes["KZT"] {
				if snapshot.Quotes["KZT"][i].Date == "2025-12-31" {
					snapshot.Quotes["KZT"][i].Rate = "460"
				}
			}
		}
		return snapshot, nil
	})
	cache := NewHistoricalCache(db, provider, clock.Now)
	if _, _, err := cache.Get(context.Background(), historyDate("2025-12-30"), historyDate("2025-12-31")); err != nil || calls.Load() != 1 {
		t.Fatal("closed historical year not loaded")
	}
	clock.Advance(7 * time.Hour)
	if _, stale, err := cache.Get(context.Background(), historyDate("2025-12-30"), historyDate("2025-12-31")); err != nil || stale || calls.Load() != 1 {
		t.Fatal("closed historical year used open-year TTL")
	}
	series, stale, err := cache.Get(context.Background(), historyDate("2025-12-31"), historyDate("2026-01-03"))
	if err != nil || stale || calls.Load() != 2 {
		t.Fatal("two-year series did not fetch only missing year")
	}
	if rate, _, date, ok := series.Pair("USD", "KZT", historyDate("2025-12-31")); !ok || rate.Cmp(big.NewRat(460, 1)) != 0 || *date != "2025-12-31" {
		t.Fatal("overlapping observations did not select newer year fetch deterministically")
	}
	if !series.FetchedAt.Equal(clock.Now().Add(-7 * time.Hour)) {
		t.Fatal("series fetch metadata does not use conservative oldest book timestamp")
	}
	clock.Advance(31 * 24 * time.Hour)
	if _, stale, err := cache.Get(context.Background(), historyDate("2025-12-30"), historyDate("2025-12-31")); err != nil || stale || calls.Load() != 3 {
		t.Fatal("closed historical year did not refresh after thirty days")
	}
}

func TestIntegrationHistoricalCacheCoalescesAcrossInstances(t *testing.T) {
	db := historicalFixture(t)
	now := historyDate("2026-10-12")
	var calls atomic.Int64
	provider := historicalProviderFunc(func(ctx context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
		calls.Add(1)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(40 * time.Millisecond):
		}
		return testHistoricalSnapshot(from, to, now, true), nil
	})
	caches := []*HistoricalCache{NewHistoricalCache(db, provider, func() time.Time { return now }), NewHistoricalCache(db, provider, func() time.Time { return now })}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			if _, stale, err := caches[i%2].Get(context.Background(), historyDate("2026-09-30"), historyDate("2026-10-11")); err != nil || stale {
				t.Error("coalesced historical request failed")
			}
		}(i)
	}
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatal("concurrent historical requests fetched provider more than once")
	}
}

func TestIntegrationHistoricalCacheRejectsMalformedAtomicRefreshAndRanges(t *testing.T) {
	db := historicalFixture(t)
	now := historyDate("2026-10-12")
	provider := historicalProviderFunc(func(_ context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
		result := testHistoricalSnapshot(from, to, now, true)
		result.Quotes["KZT"] = append(result.Quotes["KZT"], Quote{Rate: "450", Date: "2026-10-13"})
		return result, nil
	})
	cache := NewHistoricalCache(db, provider, func() time.Time { return now })
	if _, _, err := cache.Get(context.Background(), historyDate("2026-10-01"), historyDate("2026-10-11")); !errors.Is(err, ErrHistoricalUnavailable) {
		t.Fatal("invalid historical response persisted")
	}
	var count int64
	if err := db.Raw("SELECT count(*) FROM fx_historical_snapshots").Scan(&count).Error; err != nil || count != 0 {
		t.Fatal("malformed historical response partially persisted")
	}
	for _, pair := range [][2]string{{"2024-12-31", "2026-01-01"}, {"2026-10-12", "2026-10-13"}, {"2026-10-11", "2026-10-10"}, {"1899-12-31", "1900-01-01"}} {
		if _, _, err := cache.Get(context.Background(), historyDate(pair[0]), historyDate(pair[1])); !errors.Is(err, ErrHistoricalUnavailable) {
			t.Fatal("unbounded or invalid historical range accepted")
		}
	}
}

func TestIntegrationHistoricalCacheCancellationAndOldCompleteFallback(t *testing.T) {
	db := historicalFixture(t)
	clock := &historicalTestClock{now: historyDate("2026-10-12")}
	entered, release := make(chan struct{}), make(chan struct{})
	provider := historicalProviderFunc(func(ctx context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
		close(entered)
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-release:
			return testHistoricalSnapshot(from, to, clock.Now(), true), nil
		}
	})
	cache := NewHistoricalCache(db, provider, clock.Now)
	other := NewHistoricalCache(db, provider, clock.Now)
	from, to := historyDate("2025-10-01"), historyDate("2025-10-31")
	finished := make(chan error, 1)
	go func() {
		_, _, err := cache.Get(context.Background(), from, to)
		finished <- err
	}()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("historical provider did not start")
	}
	for _, candidate := range []*HistoricalCache{cache, other} {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Millisecond)
		_, _, err := candidate.Get(ctx, from, to)
		cancel()
		if !errors.Is(err, context.DeadlineExceeded) {
			close(release)
			t.Fatal("historical cache waiter ignored cancellation")
		}
	}
	close(release)
	if err := <-finished; err != nil {
		t.Fatal("cancelled waiter cancelled independent refresh")
	}
	// Historical observations do not become economically invalid merely because
	// the cache has not been refreshed this year. Their age is measured at Pair's
	// valuation date, while refresh failure is visible through the stale flag.
	clock.Advance(400 * 24 * time.Hour)
	failing := historicalProviderFunc(func(context.Context, time.Time, time.Time) (*HistoricalSnapshot, error) {
		return nil, ErrHistoricalUnavailable
	})
	series, stale, err := NewHistoricalCache(db, failing, clock.Now).Get(context.Background(), from, to)
	if err != nil || !stale {
		t.Fatal("fully covered old historical year was arbitrarily expired")
	}
	if rate, _, _, ok := series.Pair("USD", "KZT", to); !ok || rate.Cmp(big.NewRat(450, 1)) != 0 {
		t.Fatal("old economic observation was compared with current date")
	}
}

func TestIntegrationHistoricalCacheRetainsProviderFetchAge(t *testing.T) {
	db := historicalFixture(t)
	now := time.Date(2026, 10, 12, 12, 0, 0, 0, time.UTC)
	provider := historicalProviderFunc(func(_ context.Context, from, to time.Time) (*HistoricalSnapshot, error) {
		return testHistoricalSnapshot(from, to, now.Add(-7*time.Hour), true), nil
	})
	cache := NewHistoricalCache(db, provider, func() time.Time { return now })
	series, stale, err := cache.Get(context.Background(), historyDate("2026-10-01"), historyDate("2026-10-11"))
	if err != nil || !stale || !series.FetchedAt.Equal(now.Add(-7*time.Hour)) {
		t.Fatal("a refreshed observation book lost its actual fetch age")
	}
	if rate, _, _, ok := series.Pair("USD", "KZT", historyDate("2026-10-11")); !ok || rate.Cmp(big.NewRat(450, 1)) != 0 {
		t.Fatal("valid covered observations rejected because their fetch is stale")
	}
}
