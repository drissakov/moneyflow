package service

import (
	"math"
	"strconv"
	"testing"
	"time"
)

func TestDashboardFullMonthsUseFixedKazakhstanBoundaries(t *testing.T) {
	for _, count := range []int{3, 6, 12} {
		f, start, end, err := dashboardPeriod(AnalyticsDashboardFilter{Month: "2026-01", Currency: "KZT", Months: count})
		if err != nil {
			t.Fatal(err)
		}
		if f.Months != count || start.Format(time.RFC3339) != "2026-01-01T00:00:00+05:00" || end.Format(time.RFC3339) != "2026-02-01T00:00:00+05:00" || start.AddDate(0, -(count-1), 0).Month() != time.Month(14-count) {
			t.Fatal("month/year boundary drift")
		}
	}
	for _, filter := range []AnalyticsDashboardFilter{{Month: "1900-12", Currency: "KZT", Months: 3}, {Month: "2200-01", Currency: "KZT", Months: 6}, {Month: "2026-1", Currency: "KZT", Months: 12}, {Month: "2026-01", Currency: "BAD", Months: 6}, {Month: "2026-01", Currency: "KZT", Months: 4}} {
		if _, _, _, err := dashboardPeriod(filter); err == nil {
			t.Fatal("invalid dashboard accepted", filter)
		}
	}
}

func TestDashboardSignedComparisonsAndPreviousOnlyCategories(t *testing.T) {
	rootID, childID := "root", "child"
	current := []AnalyticsBucket{{CategoryID: &rootID, Name: "Food", AmountMinor: "50", Children: []AnalyticsBucket{{CategoryID: &childID, Name: "Coffee", AmountMinor: "20"}}}}
	previous := []AnalyticsBucket{{CategoryID: &rootID, Name: "Food", AmountMinor: "70", Children: []AnalyticsBucket{{CategoryID: &childID, Name: "Coffee", AmountMinor: "30"}}}, {CategoryID: nil, Name: "Uncategorized", AmountMinor: "5"}}
	result, err := compareAnalyticsBuckets(current, previous)
	if err != nil {
		t.Fatal(err)
	}
	if len(result) != 2 || result[0].DirectAmountMinor != "30" || result[0].DeltaMinor != "-20" || result[0].Children[0].DirectAmountMinor != "20" || result[1].AmountMinor != "0" || result[1].PreviousAmountMinor != "5" || result[1].DeltaMinor != "-5" || result[1].Children == nil {
		t.Fatalf("comparisons %+v", result)
	}
	max := strconv.FormatInt(math.MaxInt64, 10)
	if _, err := dashboardDifference(max, "-"+max); err == nil {
		t.Fatal("net comparison wrapped signed64")
	}
	if result, err := dashboardDifference("0", max); err != nil || result != "-"+max {
		t.Fatal("valid negative difference rejected")
	}
}
