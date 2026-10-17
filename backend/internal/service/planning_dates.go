package service

import (
	"github.com/drissakov/moneyflow/backend/internal/model"
	"time"
)

var kazakhstan = time.FixedZone("Kazakhstan", 5*60*60)

func planningDate(raw string) (time.Time, error) {
	d, err := time.Parse(time.DateOnly, raw)
	if err != nil || d.Year() < 1900 || d.Year() > 2199 || d.Format(time.DateOnly) != raw {
		return time.Time{}, Invalid("Date must be YYYY-MM-DD between 1900 and 2199.")
	}
	return d, nil
}

func planningToday(now time.Time) time.Time {
	local := now.In(kazakhstan)
	return time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, time.UTC)
}

func clampedDate(year int, month time.Month, day int) time.Time {
	first := time.Date(year, month, 1, 0, 0, 0, 0, time.UTC)
	last := first.AddDate(0, 1, -1).Day()
	if day > last {
		day = last
	}
	return time.Date(first.Year(), first.Month(), day, 0, 0, 0, 0, time.UTC)
}

// Every occurrence is calculated from the original anchor, never the previous
// clamped date: January31 -> February28 -> March31, leap day returns in2028.
func occurrenceAt(s model.Schedule, index int) time.Time {
	if index < 0 {
		return time.Time{}
	}
	switch s.Frequency {
	case "once":
		if index > 0 {
			return time.Time{}
		}
		return s.StartDate
	case "weekly":
		return s.StartDate.AddDate(0, 0, index*7*s.Interval)
	case "monthly":
		return clampedDate(s.StartDate.Year(), s.StartDate.Month()+time.Month(index*s.Interval), s.StartDate.Day())
	case "yearly":
		return clampedDate(s.StartDate.Year()+index*s.Interval, s.StartDate.Month(), s.StartDate.Day())
	}
	return time.Time{}
}

// Jump near the requested date arithmetically, then correct at most one clamp.
func occurrenceIndex(s model.Schedule, from time.Time) int {
	if !from.After(s.StartDate) {
		return 0
	}
	index := 0
	switch s.Frequency {
	case "once":
		return 1
	case "weekly":
		index = int((from.Unix()-s.StartDate.Unix())/86400) / (7 * s.Interval)
	case "monthly":
		index = ((from.Year()-s.StartDate.Year())*12 + int(from.Month()-s.StartDate.Month())) / s.Interval
	case "yearly":
		index = (from.Year() - s.StartDate.Year()) / s.Interval
	}
	if index < 0 {
		index = 0
	}
	for d := occurrenceAt(s, index); !d.IsZero() && d.Before(from); d = occurrenceAt(s, index) {
		index++
	}
	return index
}

func scheduleDates(s model.Schedule, from, to time.Time) []time.Time {
	out := make([]time.Time, 0)
	if s.EndDate != nil && s.EndDate.Before(to) {
		to = *s.EndDate
	}
	for index := occurrenceIndex(s, from); ; index++ {
		d := occurrenceAt(s, index)
		if d.IsZero() || d.After(to) || len(out) >= 110 {
			break
		}
		out = append(out, d)
	}
	return out
}

func budgetPeriod(today time.Time, day int) (time.Time, time.Time) {
	start := clampedDate(today.Year(), today.Month(), day)
	if today.Before(start) {
		start = clampedDate(today.Year(), today.Month()-1, day)
	}
	end := clampedDate(start.Year(), start.Month()+1, day)
	return start, end
}
