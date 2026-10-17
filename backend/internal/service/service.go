package service

import (
	"context"
	"errors"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/auth"
	"github.com/drissakov/moneyflow/backend/internal/fx"
	"gorm.io/gorm"
)

// MaxMinor is the maximum magnitude of one financial input, in minor units.
// Derived balances may span the full signed 64-bit range; every write checks it.
const MaxMinor int64 = 1_000_000_000_000_000

type Error struct {
	Status  int
	Code    string
	Message string
}

func (e *Error) Error() string { return e.Code }

func Invalid(message string) *Error { return &Error{400, "invalid_request", message} }

var (
	Unauthorized = &Error{401, "unauthorized", "A valid session is required."}
	NotFound     = &Error{404, "not_found", "Account was not found."}
)

type Service struct {
	DB           *gorm.DB
	sessionTTL   time.Duration
	hashSlots    chan struct{}
	dummyHash    string
	fxCache      *fx.Cache
	historicalFX *fx.HistoricalCache
	now          func() time.Time
}

type Options struct {
	FXProvider           fx.Provider
	HistoricalFXProvider fx.HistoricalProvider
	FXCacheTTL           time.Duration
	FXMaxStale           time.Duration
	Now                  func() time.Time
}

func New(db *gorm.DB, sessionTTL time.Duration, options ...Options) (*Service, error) {
	opts := Options{FXCacheTTL: 6 * time.Hour, FXMaxStale: 7 * 24 * time.Hour}
	if len(options) > 1 {
		return nil, errors.New("only one service options value is supported")
	}
	if len(options) == 1 {
		opts = options[0]
		if opts.FXCacheTTL == 0 {
			opts.FXCacheTTL = 6 * time.Hour
		}
		if opts.FXMaxStale == 0 {
			opts.FXMaxStale = 7 * 24 * time.Hour
		}
	}
	if opts.FXCacheTTL < time.Minute || opts.FXCacheTTL > 24*time.Hour || opts.FXMaxStale < time.Hour || opts.FXMaxStale > 30*24*time.Hour || opts.FXMaxStale < opts.FXCacheTTL {
		return nil, errors.New("invalid FX cache durations")
	}
	dummy, err := auth.HashPassword("This dummy value is never a real user password.")
	if err != nil {
		return nil, err
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	if opts.HistoricalFXProvider == nil {
		opts.HistoricalFXProvider, _ = opts.FXProvider.(fx.HistoricalProvider)
	}
	return &Service{DB: db, sessionTTL: sessionTTL, hashSlots: make(chan struct{}, 2), dummyHash: dummy, now: opts.Now,
		fxCache:      fx.NewCache(db, opts.FXProvider, opts.FXCacheTTL, opts.FXMaxStale, opts.Now),
		historicalFX: fx.NewHistoricalCache(db, opts.HistoricalFXProvider, opts.Now)}, nil
}

func (s *Service) acquireHashSlot(ctx context.Context) (func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	select {
	case s.hashSlots <- struct{}{}:
		return func() { <-s.hashSlots }, nil
	default:
		return nil, &Error{429, "auth_busy", "Authentication is busy. Please retry shortly."}
	}
}

func AsError(err error) (*Error, bool) {
	var target *Error
	ok := errors.As(err, &target)
	return target, ok
}
