package service

import (
	"math"
	"strings"
	"testing"
)

func TestParseMinorExactBounds(t *testing.T) {
	for _, tc := range []struct {
		raw      string
		positive bool
		want     int64
	}{
		{"0", false, 0}, {"-12345", false, -12345}, {"12345", true, 12345},
		{"1000000000000000", true, MaxMinor}, {"-1000000000000000", false, -MaxMinor},
	} {
		got, err := ParseMinor(tc.raw, tc.positive)
		if err != nil || got != tc.want {
			t.Fatalf("ParseMinor(%q, %v) = %d, %v", tc.raw, tc.positive, got, err)
		}
	}
	for _, raw := range []string{"", "-0", "00", "01", "+1", "1.0", "1e3", " 1", "1 ", "1000000000000001", "-1000000000000001", "9223372036854775808", "１", "--1"} {
		if _, err := ParseMinor(raw, false); err == nil {
			t.Errorf("accepted invalid money %q", raw)
		}
	}
	for _, raw := range []string{"0", "-1"} {
		if _, err := ParseMinor(raw, true); err == nil {
			t.Errorf("accepted nonpositive transaction %q", raw)
		}
	}
}

func TestBalanceOverflowGuard(t *testing.T) {
	for _, tc := range []struct {
		balance int64
		kind    string
		amount  int64
		want    int64
	}{
		{math.MaxInt64 - 1, "income", 1, math.MaxInt64},
		{math.MinInt64 + 1, "expense", 1, math.MinInt64},
		{0, "expense", 1299, -1299},
		{-1299, "income", 1299, 0},
	} {
		got, err := NextBalance(tc.balance, tc.kind, tc.amount)
		if err != nil || got != tc.want {
			t.Fatalf("NextBalance failed: %d %v", got, err)
		}
	}
	if _, err := NextBalance(math.MaxInt64, "income", 1); err == nil {
		t.Fatal("positive overflow accepted")
	}
	if _, err := NextBalance(math.MinInt64, "expense", 1); err == nil {
		t.Fatal("negative overflow accepted")
	}
}

func TestCredentialValidation(t *testing.T) {
	email, err := ValidateCredentials("  ALICE@example.com ", "twelve bytes password")
	if err != nil || email != "alice@example.com" {
		t.Fatalf("normalization failed: %q %v", email, err)
	}
	for _, email := range []string{"bad", "Name <a@example.com>", "a b@example.com", "é@example.com", strings.Repeat("a", 255) + "@example.com"} {
		if _, err := ValidateCredentials(email, "long enough password"); err == nil {
			t.Errorf("invalid email accepted: %q", email)
		}
	}
	for _, password := range []string{"short", strings.Repeat("a", 129), string([]byte{0xff, 0xff})} {
		if _, err := ValidateCredentials("a@example.com", password); err == nil {
			t.Error("invalid password accepted")
		}
	}
}

func TestAccountAndTimestampValidation(t *testing.T) {
	name, balance, err := ValidateAccount("  Сбережения  ", "KZT", "-1500")
	if err != nil || name != "Сбережения" || balance != -1500 {
		t.Fatal("valid Unicode account rejected")
	}
	if _, _, err := ValidateAccount("Account", "BTC", "0"); err == nil {
		t.Fatal("unsupported currency accepted")
	}
	id := "8094b783-7d58-4c50-9b04-922f56fcc21d"
	input, err := ValidateTransaction(id, "expense", "1299", "", "2026-10-09T12:00:00.123456789+05:00")
	if err != nil || input.OccurredAt.Format("2006-01-02T15:04:05.999999999Z07:00") != "2026-10-09T07:00:00.123456Z" {
		t.Fatalf("UTC normalization failed: %v %v", input.OccurredAt, err)
	}
	for _, when := range []string{"today", "0000-01-01T00:00:00Z", "0001-01-01T00:00:00+01:00", "9999-12-31T23:00:00-01:00"} {
		if _, err := ValidateTransaction(id, "income", "1", "", when); err == nil {
			t.Errorf("invalid timestamp accepted: %q", when)
		}
	}
}
