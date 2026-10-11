package fx

import (
	"errors"
	"math/big"
	"regexp"
	"strconv"
	"strings"
)

var decimalPattern = regexp.MustCompile(`^[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$`)

var ErrOverflow = errors.New("conversion exceeds signed 64-bit range")

// ParseRate parses finite positive JSON decimals without going through float64.
// Length/exponent bounds prevent an upstream value from allocating enormous ints.
func ParseRate(raw string) (*big.Rat, error) {
	if len(raw) == 0 || len(raw) > 512 || !decimalPattern.MatchString(raw) {
		return nil, errors.New("invalid decimal rate")
	}
	if pos := strings.IndexAny(raw, "eE"); pos >= 0 {
		exponent, err := strconv.Atoi(raw[pos+1:])
		if err != nil || exponent < -100 || exponent > 100 {
			return nil, errors.New("rate exponent outside supported range")
		}
	}
	rate, ok := new(big.Rat).SetString(raw)
	if !ok || rate.Sign() <= 0 {
		return nil, errors.New("rate must be positive")
	}
	return rate, nil
}

// ExactDecimalString preserves every digit of a finite upstream JSON decimal.
// Its denominator can only have factors two and five after reduction.
func ExactDecimalString(rate *big.Rat) string {
	denominator := new(big.Int).Set(rate.Denom())
	twos, fives := 0, 0
	for denominator.Bit(0) == 0 {
		denominator.Rsh(denominator, 1)
		twos++
	}
	five, remainder := big.NewInt(5), new(big.Int)
	for remainder.Mod(denominator, five).Sign() == 0 {
		denominator.Quo(denominator, five)
		fives++
	}
	if denominator.Cmp(big.NewInt(1)) != 0 {
		return DecimalString(rate)
	}
	scale := max(twos, fives)
	result := rate.FloatString(scale)
	if strings.Contains(result, ".") {
		result = strings.TrimRight(strings.TrimRight(result, "0"), ".")
	}
	return result
}

// RoundHalfEven rounds an exact rational to an integer symmetrically for either
// sign. The only financial rounding happens after both currency scales apply.
func RoundHalfEven(value *big.Rat) *big.Int {
	numerator := new(big.Int).Abs(value.Num())
	quotient, remainder := new(big.Int), new(big.Int)
	quotient.QuoRem(numerator, value.Denom(), remainder)
	comparison := new(big.Int).Lsh(remainder, 1).Cmp(value.Denom())
	if comparison > 0 || comparison == 0 && quotient.Bit(0) == 1 {
		quotient.Add(quotient, big.NewInt(1))
	}
	if value.Sign() < 0 {
		quotient.Neg(quotient)
	}
	return quotient
}

func pow10(scale int) *big.Int {
	return new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(scale)), nil)
}

func ConvertMinor(amount int64, from, to Currency, rate *big.Rat) (int64, error) {
	if rate == nil || rate.Sign() <= 0 {
		return 0, errors.New("invalid conversion rate")
	}
	value := new(big.Rat).Mul(new(big.Rat).SetInt64(amount), rate)
	value.Mul(value, new(big.Rat).SetFrac(pow10(to.FractionDigits), pow10(from.FractionDigits)))
	rounded := RoundHalfEven(value)
	if !rounded.IsInt64() {
		return 0, ErrOverflow
	}
	return rounded.Int64(), nil
}

// DecimalString gives a decimal display rate. Recurring cross rates are rounded
// to 24 decimal places; exact conversion always uses the original rational.
// Tiny values get additional places so a positive rate is never shown as zero.
func DecimalString(rate *big.Rat) string {
	scale := max(24, 24+len(rate.Denom().String())-len(rate.Num().String()))
	integer := RoundHalfEven(new(big.Rat).Mul(rate, new(big.Rat).SetInt(pow10(scale))))
	digits := integer.String()
	if len(digits) <= scale {
		digits = strings.Repeat("0", scale+1-len(digits)) + digits
	}
	result := digits[:len(digits)-scale] + "." + digits[len(digits)-scale:]
	return strings.TrimRight(strings.TrimRight(result, "0"), ".")
}
