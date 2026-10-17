package fx

import (
	"context"
	"errors"
	"math/big"
	"sort"
	"time"
)

// MaxPreviousDays matches the provider's daily carry-forward window. Weekends
// are not special: some central banks publish observations on those dates too.
const MaxPreviousDays = 14

var ErrHistoricalUnavailable = errors.New("historical exchange rates unavailable")

type HistoricalProvider interface {
	FetchRange(context.Context, time.Time, time.Time) (*HistoricalSnapshot, error)
}

type HistoricalSnapshot struct {
	FetchedAt time.Time
	FromDate  string
	ToDate    string
	Quotes    map[string][]Quote
}

// HistoricalSeries is an immutable observation book assembled for one report.
// FetchedAt is the oldest fetch contributing to the book, not a market date.
type HistoricalSeries struct {
	FetchedAt time.Time
	quotes    map[string][]Quote
}

func historicalDay(value time.Time) time.Time {
	return time.Date(value.Year(), value.Month(), value.Day(), 0, 0, 0, 0, time.UTC)
}

func historicalToday(value time.Time) time.Time {
	return historicalDay(value.In(time.FixedZone("UTC+05:00", 5*60*60)))
}

// Pair selects each anchor quote independently as of the requested calendar day.
// An observation after that day can never influence an earlier conversion.
func (s *HistoricalSeries) Pair(from, to string, valuationDate time.Time) (*big.Rat, *string, *string, bool) {
	if _, ok := Lookup(from); !ok {
		return nil, nil, nil, false
	}
	if _, ok := Lookup(to); !ok {
		return nil, nil, nil, false
	}
	if from == to {
		return newOne(), nil, nil, true
	}
	if s == nil {
		return nil, nil, nil, false
	}
	valuationDate = historicalDay(valuationDate)
	read := func(currency string) (*big.Rat, *string, bool) {
		if currency == "USD" {
			return newOne(), nil, true
		}
		quotes := s.quotes[currency]
		key := valuationDate.Format(time.DateOnly)
		index := sort.Search(len(quotes), func(i int) bool { return quotes[i].Date > key }) - 1
		if index < 0 {
			return nil, nil, false
		}
		quote := quotes[index]
		date, err := time.Parse(time.DateOnly, quote.Date)
		if err != nil || valuationDate.Sub(date) > MaxPreviousDays*24*time.Hour {
			return nil, nil, false
		}
		rate, err := ParseRate(quote.Rate)
		return rate, &quote.Date, err == nil
	}
	source, sourceDate, sourceOK := read(from)
	target, targetDate, targetOK := read(to)
	if !sourceOK || !targetOK {
		return nil, nil, nil, false
	}
	return new(big.Rat).Quo(target, source), sourceDate, targetDate, true
}
