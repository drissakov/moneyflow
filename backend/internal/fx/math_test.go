package fx

import (
	"math"
	"math/big"
	"strings"
	"testing"
)

func mustRate(t *testing.T, raw string) *big.Rat {
	t.Helper()
	rate, err := ParseRate(raw)
	if err != nil {
		t.Fatal(err)
	}
	return rate
}

func TestExactConversionScalesAndHalfEven(t *testing.T) {
	usd, _ := Lookup("USD")
	jpy, _ := Lookup("JPY")
	kwd, _ := Lookup("KWD")
	for _, tc := range []struct {
		amount   int64
		from, to Currency
		rate     string
		want     int64
	}{
		{199, usd, jpy, "150.5", 299},
		{100, usd, jpy, "150.5", 150},
		{300, usd, jpy, "150.5", 452},
		{-100, usd, jpy, "150.5", -150},
		{-300, usd, jpy, "150.5", -452},
		{100, usd, kwd, "0.305", 305},
		{1, kwd, usd, "1", 0},
		{15, kwd, usd, "1", 2},
		{-15, kwd, usd, "1", -2},
		{9007199254740993, usd, usd, "1", 9007199254740993},
		{math.MinInt64, usd, usd, "1", math.MinInt64},
	} {
		got, err := ConvertMinor(tc.amount, tc.from, tc.to, mustRate(t, tc.rate))
		if err != nil || got != tc.want {
			t.Fatalf("convert(%d %s -> %s)=%d, %v; want%d", tc.amount, tc.from.Code, tc.to.Code, got, err, tc.want)
		}
	}
	if _, err := ConvertMinor(math.MaxInt64, usd, usd, mustRate(t, "2")); err != ErrOverflow {
		t.Fatal("positive overflow was not rejected")
	}
	if _, err := ConvertMinor(math.MinInt64, usd, usd, mustRate(t, "2")); err != ErrOverflow {
		t.Fatal("negative overflow was not rejected")
	}
}

func TestFiniteDecimalPrecisionAndMalformedRates(t *testing.T) {
	raw := "0.123456789012345678901234567890123456789"
	if got := ExactDecimalString(mustRate(t, raw)); got != raw {
		t.Fatalf("upstream precision lost: %s", got)
	}
	if got := ExactDecimalString(mustRate(t, "1.2300e-4")); got != "0.000123" {
		t.Fatal("scientific decimal normalization failed")
	}
	if got := DecimalString(big.NewRat(1, 3)); got != "0.333333333333333333333333" {
		t.Fatal("cross-rate display precision changed")
	}
	if got := DecimalString(mustRate(t, "1e-100")); got == "0" || got == "" {
		t.Fatal("positive rate displayed as zero")
	}
	boundary := new(big.Rat).Quo(mustRate(t, "1e-100"), mustRate(t, "1"+strings.Repeat("0", 122)+"e100"))
	if got := DecimalString(boundary); got == "0" || len(got) < 324 {
		t.Fatal("accepted extreme cross rate displayed as zero")
	}
	for _, raw := range []string{"", "0", "-1", "+1", "NaN", "Infinity", "1/3", "1e1000000", "0x10", strings.Repeat("9", 513)} {
		if _, err := ParseRate(raw); err == nil {
			t.Errorf("malformed rate accepted: %q", raw)
		}
	}
}

func TestHalfEvenSymmetricTies(t *testing.T) {
	for _, tc := range []struct{ n, d, want int64 }{
		{1, 2, 0}, {3, 2, 2}, {5, 2, 2}, {7, 2, 4}, {-1, 2, 0}, {-3, 2, -2}, {-5, 2, -2}, {-7, 2, -4},
	} {
		if got := RoundHalfEven(big.NewRat(tc.n, tc.d)); got.Int64() != tc.want {
			t.Fatalf("%d/%d rounds to%s", tc.n, tc.d, got)
		}
	}
}
