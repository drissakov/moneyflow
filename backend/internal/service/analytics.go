package service

import (
	"context"
	"math"
	"sort"
	"strconv"
	"time"
	_ "time/tzdata" // IANA zones must also work on Windows and minimal containers.
)

type AnalyticsBucket struct {
	CategoryID  *string           `json:"category_id"`
	Name        string            `json:"name"`
	AmountMinor string            `json:"amount_minor"`
	Children    []AnalyticsBucket `json:"children"`
}

type AnalyticsView struct {
	Month        string            `json:"month"`
	Currency     string            `json:"currency"`
	Timezone     string            `json:"timezone"`
	IncomeMinor  string            `json:"income_minor"`
	ExpenseMinor string            `json:"expense_minor"`
	Income       []AnalyticsBucket `json:"income"`
	Expenses     []AnalyticsBucket `json:"expenses"`
}

func AnalyticsPeriod(month, currency, timezone string) (time.Time, time.Time, error) {
	if err := ValidateCurrency(currency); err != nil {
		return time.Time{}, time.Time{}, err
	}
	parsed, err := time.Parse("2006-01", month)
	if err != nil || parsed.Year() < 1 || parsed.Year() > 9999 || parsed.Format("2006-01") != month {
		return time.Time{}, time.Time{}, Invalid("Month must be YYYY-MM between years 0001 and 9999.")
	}
	if timezone == "" || timezone == "Local" || len(timezone) > 100 {
		return time.Time{}, time.Time{}, Invalid("Timezone must be an IANA timezone identifier.")
	}
	zone, err := time.LoadLocation(timezone)
	if err != nil {
		return time.Time{}, time.Time{}, Invalid("Timezone must be an IANA timezone identifier.")
	}
	start := time.Date(parsed.Year(), parsed.Month(), 1, 0, 0, 0, 0, zone)
	end := time.Date(parsed.Year(), parsed.Month()+1, 1, 0, 0, 0, 0, zone)
	return start.UTC(), end.UTC(), nil
}

type analyticsRow struct {
	Kind        string
	CategoryID  *string
	Name        string
	ParentID    *string
	ParentName  string
	AmountMinor string
}

func analyticsOverflow() error {
	return &Error{409, "analytics_overflow", "Analytics total exceeds the supported signed 64-bit range."}
}

func addAnalyticsAmount(current, amount int64) (int64, error) {
	if amount < 0 || current > math.MaxInt64-amount {
		return 0, analyticsOverflow()
	}
	return current + amount, nil
}

type accumulatedBucket struct {
	view   AnalyticsBucket
	amount int64
}

func buildAnalytics(month, currency, timezone string, rows []analyticsRow) (AnalyticsView, error) {
	result := AnalyticsView{Month: month, Currency: currency, Timezone: timezone, IncomeMinor: "0", ExpenseMinor: "0",
		Income: make([]AnalyticsBucket, 0), Expenses: make([]AnalyticsBucket, 0)}
	groups := map[string]*accumulatedBucket{}
	var income, expenses int64
	for _, row := range rows {
		amount, err := strconv.ParseInt(row.AmountMinor, 10, 64)
		if err != nil || amount <= 0 {
			return result, analyticsOverflow()
		}
		if row.Kind == "income" {
			income, err = addAnalyticsAmount(income, amount)
		} else {
			expenses, err = addAnalyticsAmount(expenses, amount)
		}
		if err != nil {
			return result, err
		}
		rootID, name := row.CategoryID, row.Name
		if row.ParentID != nil {
			rootID, name = row.ParentID, row.ParentName
		}
		key := row.Kind + ":uncategorized"
		if rootID != nil {
			key = row.Kind + ":" + *rootID
		}
		root := groups[key]
		if root == nil {
			root = &accumulatedBucket{view: AnalyticsBucket{CategoryID: rootID, Name: name, Children: make([]AnalyticsBucket, 0)}}
			groups[key] = root
		}
		root.amount, err = addAnalyticsAmount(root.amount, amount)
		if err != nil {
			return result, err
		}
		if row.ParentID != nil {
			root.view.Children = append(root.view.Children, AnalyticsBucket{CategoryID: row.CategoryID, Name: row.Name,
				AmountMinor: row.AmountMinor, Children: make([]AnalyticsBucket, 0)})
		}
	}
	for key, root := range groups {
		root.view.AmountMinor = strconv.FormatInt(root.amount, 10)
		sortAnalyticsBuckets(root.view.Children)
		if key[:6] == "income" {
			result.Income = append(result.Income, root.view)
		} else {
			result.Expenses = append(result.Expenses, root.view)
		}
	}
	sortAnalyticsBuckets(result.Income)
	sortAnalyticsBuckets(result.Expenses)
	result.IncomeMinor, result.ExpenseMinor = strconv.FormatInt(income, 10), strconv.FormatInt(expenses, 10)
	return result, nil
}

func sortAnalyticsBuckets(items []AnalyticsBucket) {
	sort.Slice(items, func(i, j int) bool {
		a, _ := strconv.ParseInt(items[i].AmountMinor, 10, 64)
		b, _ := strconv.ParseInt(items[j].AmountMinor, 10, 64)
		if a != b {
			return a > b
		}
		if items[i].Name != items[j].Name {
			return items[i].Name < items[j].Name
		}
		return categorySortID(items[i].CategoryID) < categorySortID(items[j].CategoryID)
	})
}

func categorySortID(id *string) string {
	if id == nil {
		return ""
	}
	return *id
}

func (s *Service) Analytics(ctx context.Context, userID, month, currency, timezone string) (AnalyticsView, error) {
	start, end, err := AnalyticsPeriod(month, currency, timezone)
	if err != nil {
		return AnalyticsView{}, err
	}
	rows := make([]analyticsRow, 0)
	// PostgreSQL aggregates every matching entry and SUM(bigint) is numeric.
	// Parsing the grouped values and totals below provides explicit overflow errors.
	err = s.DB.WithContext(ctx).Raw(`SELECT t.kind, t.category_id,
		COALESCE(c.name, 'Без категории') AS name, c.parent_id,
		COALESCE(p.name, '') AS parent_name, SUM(t.amount_minor)::text AS amount_minor
		FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
		LEFT JOIN categories p ON p.id = c.parent_id
		WHERE t.user_id = ? AND t.currency = ? AND t.occurred_at >= ? AND t.occurred_at < ?
		AND NOT t.exclude_from_analytics
		GROUP BY t.kind, t.category_id, c.name, c.parent_id, p.name`, userID, currency, start, end).Scan(&rows).Error
	if err != nil {
		return AnalyticsView{}, err
	}
	return buildAnalytics(month, currency, timezone, rows)
}
