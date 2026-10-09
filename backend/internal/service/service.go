package service

import (
	"context"
	"errors"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/auth"
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
	DB         *gorm.DB
	sessionTTL time.Duration
	hashSlots  chan struct{}
	dummyHash  string
}

func New(db *gorm.DB, sessionTTL time.Duration) (*Service, error) {
	dummy, err := auth.HashPassword("This dummy value is never a real user password.")
	if err != nil {
		return nil, err
	}
	return &Service{DB: db, sessionTTL: sessionTTL, hashSlots: make(chan struct{}, 2), dummyHash: dummy}, nil
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
