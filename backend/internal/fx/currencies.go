// Package fx provides curated currency metadata, exact conversion, and a shared
// USD-anchored Frankfurter cache. Original ledger amounts are never rewritten.
package fx

const ProviderName = "Frankfurter"

type Currency struct {
	Code           string `json:"code"`
	Name           string `json:"name"`
	Symbol         string `json:"symbol"`
	FractionDigits int    `json:"fraction_digits"`
}

// Fraction digits come from ISO 4217, not Frankfurter's currency catalogue.
// The deliberately limited set is shared by persistence, conversion, and API DTOs.
var catalogue = []Currency{
	{"KZT", "Kazakhstani Tenge", "₸", 2},
	{"USD", "US Dollar", "$", 2},
	{"EUR", "Euro", "€", 2},
	{"JPY", "Japanese Yen", "¥", 0},
	{"KWD", "Kuwaiti Dinar", "د.ك", 3},
	{"RUB", "Russian Ruble", "₽", 2},
	{"GBP", "British Pound", "£", 2},
	{"CHF", "Swiss Franc", "CHF", 2},
	{"AUD", "Australian Dollar", "A$", 2},
	{"CAD", "Canadian Dollar", "C$", 2},
	{"CNY", "Chinese Yuan", "¥", 2},
}

func Currencies() []Currency { return append([]Currency(nil), catalogue...) }

func Lookup(code string) (Currency, bool) {
	for _, c := range catalogue {
		if c.Code == code {
			return c, true
		}
	}
	return Currency{}, false
}

func Codes() []string {
	result := make([]string, 0, len(catalogue))
	for _, c := range catalogue {
		result = append(result, c.Code)
	}
	return result
}
