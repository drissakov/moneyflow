package service

import (
	"github.com/drissakov/moneyflow/backend/internal/model"
	"testing"
	"time"
)

func TestPlanningAnchorsAndKazakhstanDates(t *testing.T) {
	cases := []struct {
		start, frequency string
		interval, index  int
		want             string
	}{
		{"2025-01-31", "monthly", 1, 1, "2025-02-28"}, {"2025-01-31", "monthly", 1, 2, "2025-03-31"},
		{"2024-02-29", "yearly", 1, 1, "2025-02-28"}, {"2024-02-29", "yearly", 1, 4, "2028-02-29"},
		{"2024-01-31", "monthly", 2, 2, "2024-05-31"}, {"2026-10-17", "weekly", 2, 2, "2026-11-14"},
	}
	for _, c := range cases {
		start, _ := planningDate(c.start)
		s := model.Schedule{StartDate: start, Frequency: c.frequency, Interval: c.interval}
		if got := occurrenceAt(s, c.index).Format(time.DateOnly); got != c.want {
			t.Fatalf("%+v got%s", c, got)
		}
		if got := occurrenceIndex(s, occurrenceAt(s, c.index)); got != c.index {
			t.Fatalf("inverse index=%d want%d", got, c.index)
		}
	}
	now := time.Date(2026, 10, 16, 20, 1, 0, 0, time.UTC)
	if got := planningToday(now).Format(time.DateOnly); got != "2026-10-17" {
		t.Fatal("Kazakhstan date boundary", got)
	}
	today, _ := planningDate("2025-03-01")
	from, to := budgetPeriod(today, 31)
	if from.Format(time.DateOnly) != "2025-02-28" || to.Format(time.DateOnly) != "2025-03-31" {
		t.Fatal("budget anchor drift")
	}
	old, _ := planningDate("1900-01-01")
	future, _ := planningDate("2199-01-01")
	s := model.Schedule{StartDate: old, Frequency: "weekly", Interval: 1}
	index := occurrenceIndex(s, future)
	date := occurrenceAt(s, index)
	if date.Before(future) || date.Sub(future) > 6*24*time.Hour {
		t.Fatal("long-range weekly arithmetic overflow")
	}
}

func TestPlanningRangeAndEndAreBounded(t *testing.T) {
	now := time.Date(2026, 10, 17, 10, 0, 0, 0, time.UTC)
	for _, pair := range [][2]string{{"2026-10-18", "2026-10-17"}, {"2026-01-01", "2027-12-31"}, {"2024-01-01", "2024-01-02"}, {"2026-02-30", "2026-03-01"}} {
		if _, _, err := parsePlanningRange(now, pair[0], pair[1]); err == nil {
			t.Fatal("unbounded/invalid range accepted", pair)
		}
	}
	start, _ := planningDate("2025-01-31")
	end, _ := planningDate("2025-03-30")
	s := model.Schedule{StartDate: start, EndDate: &end, Frequency: "monthly", Interval: 1}
	to, _ := planningDate("2026-12-31")
	if dates := scheduleDates(s, start, to); len(dates) != 2 || dates[1].Format(time.DateOnly) != "2025-02-28" {
		t.Fatal("end date ignored", dates)
	}
	s.Frequency = "once"
	if len(scheduleDates(s, start, to)) != 1 {
		t.Fatal("once repeated")
	}
}
