package service

import (
	"crypto/sha256"
	"encoding/json"
	"math"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestCategoryValidationAndLegacyFingerprint(t *testing.T) {
	id := "10000000-0000-4000-8000-000000000001"
	name, parent, err := ValidateCategory("expense", "  Покупка   автомобиля  ", nil)
	if err != nil || name != "Покупка автомобиля" || parent != nil {
		t.Fatal("custom category normalization failed")
	}
	if _, _, err := ValidateCategory("expense", strings.Repeat("я", 100), &id); err != nil {
		t.Fatal("100 Unicode characters rejected")
	}
	for _, tc := range []struct {
		kind, name string
		parent     *string
	}{
		{"expense", "", nil}, {"expense", " \t ", nil}, {"expense", strings.Repeat("я", 101), nil},
		{"expense", "name\x00", nil}, {"expense", string([]byte{0xff}), nil}, {"other", "Name", nil},
		{"income", "Name", &id}, {"expense", "Name", pointer("invalid")},
	} {
		if _, _, err := ValidateCategory(tc.kind, tc.name, tc.parent); err == nil {
			t.Fatalf("invalid category accepted: %#v", tc)
		}
	}
	input, err := ValidateTransaction("8094b783-7d58-4c50-9b04-922f56fcc21d", "expense", "1299", "", "2026-10-09T12:00:00+05:00")
	if err != nil {
		t.Fatal(err)
	}
	encoded, _ := json.Marshal(input)
	legacy := `{"account_id":"8094b783-7d58-4c50-9b04-922f56fcc21d","kind":"expense","amount_minor":"1299","note":"","occurred_at":"2026-10-09T07:00:00Z"}`
	if string(encoded) != legacy {
		t.Fatal("adding optional category changed a legacy idempotency fingerprint")
	}
	old := sha256.Sum256(encoded)
	input.CategoryID = &id
	withCategory, _ := json.Marshal(input)
	if sha256.Sum256(withCategory) == old {
		t.Fatal("idempotency fingerprint did not include category")
	}
}

func pointer(value string) *string { return &value }

func TestAnalyticsCalendarMonthsAndValidation(t *testing.T) {
	for _, tc := range []struct {
		month, zone, start, end string
	}{
		{"2026-10", "Asia/Qyzylorda", "2026-09-30T19:00:00Z", "2026-10-31T19:00:00Z"},
		{"2026-03", "America/New_York", "2026-03-01T05:00:00Z", "2026-04-01T04:00:00Z"},
		{"2024-02", "UTC", "2024-02-01T00:00:00Z", "2024-03-01T00:00:00Z"},
	} {
		start, end, err := AnalyticsPeriod(tc.month, "KZT", tc.zone)
		if err != nil || start.Format(time.RFC3339) != tc.start || end.Format(time.RFC3339) != tc.end {
			t.Fatalf("incorrect local calendar boundaries: %v, %v, %v", start, end, err)
		}
	}
	for _, month := range []string{"", "2026-1", "2026-13", "0000-01", "2026-10-01", "2026-10 "} {
		if _, _, err := AnalyticsPeriod(month, "KZT", "UTC"); err == nil {
			t.Fatalf("invalid analytics month accepted: %q", month)
		}
	}
	for _, zone := range []string{"", "Local", "Not/A_Zone", "../UTC"} {
		if _, _, err := AnalyticsPeriod("2026-10", "KZT", zone); err == nil {
			t.Fatalf("invalid analytics timezone accepted: %q", zone)
		}
	}
	if _, _, err := AnalyticsPeriod("2026-10", "BTC", "UTC"); err == nil {
		t.Fatal("unsupported analytics currency accepted")
	}
}

func TestAnalyticsRollupAndExactOverflow(t *testing.T) {
	root, child, incomeID := pointer("root"), pointer("child"), pointer("salary")
	rows := []analyticsRow{
		{Kind: "expense", CategoryID: root, Name: "Food", AmountMinor: "20"},
		{Kind: "expense", CategoryID: child, Name: "Coffee", ParentID: root, ParentName: "Food", AmountMinor: "50"},
		{Kind: "expense", Name: "Без категории", AmountMinor: "1"},
		{Kind: "income", CategoryID: incomeID, Name: "Salary", AmountMinor: "100"},
	}
	view, err := buildAnalytics("2026-10", "KZT", "UTC", rows)
	if err != nil || view.ExpenseMinor != "71" || view.IncomeMinor != "100" || len(view.Expenses) != 2 {
		t.Fatal("wrong totals")
	}
	food := view.Expenses[0]
	if food.AmountMinor != "70" || len(food.Children) != 1 || food.Children[0].AmountMinor != "50" {
		t.Fatal("child was omitted or counted twice")
	}
	empty, err := buildAnalytics("2026-10", "KZT", "UTC", nil)
	if err != nil || empty.Income == nil || empty.Expenses == nil || empty.IncomeMinor != "0" || empty.ExpenseMinor != "0" {
		t.Fatal("empty analytics must have arrays and zero totals")
	}
	max := strconv.FormatInt(math.MaxInt64, 10)
	if _, err := buildAnalytics("2026-10", "KZT", "UTC", []analyticsRow{{Kind: "income", Name: "Без категории", AmountMinor: max}}); err != nil {
		t.Fatal("maximum total rejected")
	}
	for _, overflow := range [][]analyticsRow{
		{{Kind: "income", AmountMinor: "9223372036854775808"}},
		{{Kind: "income", CategoryID: incomeID, Name: "Salary", AmountMinor: max}, {Kind: "income", AmountMinor: "1"}},
		{{Kind: "expense", CategoryID: root, AmountMinor: max}, {Kind: "expense", CategoryID: child, ParentID: root, AmountMinor: "1"}},
	} {
		if _, err := buildAnalytics("2026-10", "KZT", "UTC", overflow); err == nil {
			t.Fatal("analytics overflow accepted")
		}
	}
}
