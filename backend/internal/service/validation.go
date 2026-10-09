package service

import (
	"net/mail"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
)

func ValidateCredentials(email, password string) (string, error) {
	email = strings.ToLower(strings.TrimSpace(email))
	if len(email) > 254 || len(email) < 3 {
		return "", Invalid("Email must be a valid address of at most 254 bytes.")
	}
	for _, r := range email {
		if r <= 32 || r >= 127 {
			return "", Invalid("Email must contain only ASCII address characters.")
		}
	}
	parsed, err := mail.ParseAddress(email)
	if err != nil || parsed.Name != "" || parsed.Address != email || !strings.Contains(email, "@") {
		return "", Invalid("Email must be a valid address.")
	}
	if !utf8.ValidString(password) || len(password) < 12 || len(password) > 128 {
		return "", Invalid("Password must contain between 12 and 128 UTF-8 bytes.")
	}
	return email, nil
}

func ParseMinor(raw string, positive bool) (int64, error) {
	invalid := Invalid("Amount must be a canonical decimal integer string with magnitude at most 1000000000000000.")
	if raw == "" || len(raw) > 17 || raw == "-0" {
		return 0, invalid
	}
	digits := raw
	if strings.HasPrefix(raw, "-") {
		if positive {
			return 0, invalid
		}
		digits = raw[1:]
	}
	if digits == "" || len(digits) > 1 && digits[0] == '0' {
		return 0, invalid
	}
	for _, r := range digits {
		if r < '0' || r > '9' {
			return 0, invalid
		}
	}
	value, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || value < -MaxMinor || value > MaxMinor || positive && value <= 0 {
		return 0, invalid
	}
	return value, nil
}

func ParseID(raw string) (string, error) {
	id, err := uuid.Parse(raw)
	if err != nil || id == uuid.Nil || len(raw) != 36 {
		return "", Invalid("An identifier must be a nonzero UUID in hyphenated form.")
	}
	return id.String(), nil
}

func ValidateAccount(name, currency, opening string) (string, int64, error) {
	name = strings.TrimSpace(name)
	if !utf8.ValidString(name) || utf8.RuneCountInString(name) < 1 || utf8.RuneCountInString(name) > 100 {
		return "", 0, Invalid("Account name must contain 1 to 100 characters.")
	}
	switch currency {
	case "USD", "EUR", "KZT", "JPY", "KWD":
	default:
		return "", 0, Invalid("Currency must be USD, EUR, KZT, JPY, or KWD.")
	}
	value, err := ParseMinor(opening, false)
	return name, value, err
}

type CreateTransactionInput struct {
	AccountID  string    `json:"account_id"`
	Kind       string    `json:"kind"`
	Amount     int64     `json:"-"`
	AmountText string    `json:"amount_minor"`
	Note       string    `json:"note"`
	OccurredAt time.Time `json:"occurred_at"`
}

func ValidateTransaction(accountID, kind, amount, note, occurred string) (CreateTransactionInput, error) {
	var input CreateTransactionInput
	id, err := ParseID(accountID)
	if err != nil {
		return input, err
	}
	if kind != "expense" && kind != "income" {
		return input, Invalid("Kind must be expense or income.")
	}
	value, err := ParseMinor(amount, true)
	if err != nil {
		return input, err
	}
	if !utf8.ValidString(note) || utf8.RuneCountInString(note) > 500 {
		return input, Invalid("Note must contain at most 500 characters.")
	}
	when, err := time.Parse(time.RFC3339Nano, occurred)
	if err != nil || when.Year() < 1 || when.Year() > 9999 {
		return input, Invalid("Occurred_at must be an RFC3339 timestamp between years 0001 and 9999.")
	}
	// PostgreSQL has microsecond precision. Persist and fingerprint the same value.
	when = when.UTC().Truncate(time.Microsecond)
	if when.Year() < 1 || when.Year() > 9999 {
		return input, Invalid("Occurred_at must resolve to a UTC year between 0001 and 9999.")
	}
	input = CreateTransactionInput{AccountID: id, Kind: kind, Amount: value, AmountText: amount, Note: note, OccurredAt: when}
	return input, nil
}
