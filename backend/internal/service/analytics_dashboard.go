package service

import (
	"context"
	"database/sql"
	"fmt"
	"math/big"
	"sort"
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/model"
	"gorm.io/gorm"
)

type AnalyticsDashboardFilter struct {
	Month     string
	Currency  string
	Months    int
	AccountID string
}

type AnalyticsComparison struct {
	IncomeMinor          string `json:"income_minor"`
	ExpenseMinor         string `json:"expense_minor"`
	NetMinor             string `json:"net_minor"`
	PreviousIncomeMinor  string `json:"previous_income_minor"`
	PreviousExpenseMinor string `json:"previous_expense_minor"`
	PreviousNetMinor     string `json:"previous_net_minor"`
	IncomeDeltaMinor     string `json:"income_delta_minor"`
	ExpenseDeltaMinor    string `json:"expense_delta_minor"`
	NetDeltaMinor        string `json:"net_delta_minor"`
}

type AnalyticsSeriesPoint struct {
	Month               string `json:"month"`
	From                string `json:"from"`
	To                  string `json:"to"`
	IncomeMinor         string `json:"income_minor"`
	ExpenseMinor        string `json:"expense_minor"`
	NetMinor            string `json:"net_minor"`
	OpeningBalanceMinor string `json:"opening_balance_minor"`
	ClosingBalanceMinor string `json:"closing_balance_minor"`
	LedgerChangeMinor   string `json:"ledger_change_minor"`
}

type AnalyticsComparisonBucket struct {
	CategoryID          *string                     `json:"category_id"`
	Name                string                      `json:"name"`
	AmountMinor         string                      `json:"amount_minor"`
	DirectAmountMinor   string                      `json:"direct_amount_minor"`
	PreviousAmountMinor string                      `json:"previous_amount_minor"`
	DeltaMinor          string                      `json:"delta_minor"`
	Children            []AnalyticsComparisonBucket `json:"children"`
}

type AnalyticsDashboardView struct {
	Month        string                      `json:"month"`
	Currency     string                      `json:"currency"`
	Timezone     string                      `json:"timezone"`
	Months       int                         `json:"months"`
	AccountID    *string                     `json:"account_id"`
	AccountIDs   []string                    `json:"account_ids"`
	From         string                      `json:"from"`
	To           string                      `json:"to"`
	AsOf         time.Time                   `json:"as_of"`
	Today        string                      `json:"today"`
	PeriodStatus string                      `json:"period_status"`
	Summary      AnalyticsComparison         `json:"summary"`
	Series       []AnalyticsSeriesPoint      `json:"series"`
	Income       []AnalyticsComparisonBucket `json:"income"`
	Expenses     []AnalyticsComparisonBucket `json:"expenses"`
}

func dashboardPeriod(f AnalyticsDashboardFilter) (AnalyticsDashboardFilter, time.Time, time.Time, error) {
	if err := ValidateCurrency(f.Currency); err != nil {
		return f, time.Time{}, time.Time{}, err
	}
	month, err := time.Parse("2006-01", f.Month)
	if err != nil || month.Year() < 1901 || month.Year() > 2199 || month.Format("2006-01") != f.Month {
		return f, time.Time{}, time.Time{}, Invalid("Month must be YYYY-MM between 1901 and 2199.")
	}
	if f.Months != 3 && f.Months != 6 && f.Months != 12 {
		return f, time.Time{}, time.Time{}, Invalid("Months must be 3, 6 or 12.")
	}
	if f.AccountID != "" {
		id, err := ParseID(f.AccountID)
		if err != nil {
			return f, time.Time{}, time.Time{}, err
		}
		f.AccountID = id
	}
	start := time.Date(month.Year(), month.Month(), 1, 0, 0, 0, 0, kazakhstan)
	return f, start, start.AddDate(0, 1, 0), nil
}

type dashboardMonthlyRow struct {
	Month             string
	IncomeMinor       string
	ExpenseMinor      string
	LedgerChangeMinor string
}

type dashboardCategoryRow struct {
	Month       string
	Kind        string
	CategoryID  *string
	Name        string
	ParentID    *string
	ParentName  string
	AmountMinor string
}

func dashboardInteger(raw string) (*big.Int, error) {
	value, ok := new(big.Int).SetString(raw, 10)
	if !ok {
		return nil, analyticsOverflow()
	}
	return value, nil
}
func dashboardResult(value *big.Int) (string, error) {
	if !value.IsInt64() {
		return "", analyticsOverflow()
	}
	return value.String(), nil
}
func dashboardDifference(a, b string) (string, error) {
	av, err := dashboardInteger(a)
	if err != nil {
		return "", err
	}
	bv, err := dashboardInteger(b)
	if err != nil {
		return "", err
	}
	return dashboardResult(new(big.Int).Sub(av, bv))
}

// A dashboard is one read-only MVCC snapshot. It never waits on a user write
// advisory lock, runs an FX request or writes cache/planning/ledger data.
func (s *Service) AnalyticsDashboard(ctx context.Context, userID string, filter AnalyticsDashboardFilter) (AnalyticsDashboardView, error) {
	f, start, end, err := dashboardPeriod(filter)
	if err != nil {
		return AnalyticsDashboardView{}, err
	}
	now := s.now().UTC().Truncate(time.Microsecond)
	today := planningToday(now)
	status := "current"
	if f.Month < today.Format("2006-01") {
		status = "past"
	} else if f.Month > today.Format("2006-01") {
		status = "future"
	}
	view := AnalyticsDashboardView{Month: f.Month, Currency: f.Currency, Timezone: "UTC+05:00", Months: f.Months, AccountIDs: make([]string, 0), From: start.Format(time.RFC3339), To: end.Format(time.RFC3339), AsOf: now, Today: today.Format(time.DateOnly), PeriodStatus: status, Series: make([]AnalyticsSeriesPoint, 0, f.Months), Income: make([]AnalyticsComparisonBucket, 0), Expenses: make([]AnalyticsComparisonBucket, 0)}
	if f.AccountID != "" {
		view.AccountID = &f.AccountID
	}
	err = s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var accounts []model.Account
		query := tx.Where("user_id=?", userID)
		if f.AccountID != "" {
			query = query.Where("id=?", f.AccountID)
		} else {
			query = query.Where("currency=?", f.Currency)
		}
		if err := query.Select("id,currency,opening_balance_minor").Order("id").Find(&accounts).Error; err != nil {
			return err
		}
		if f.AccountID != "" && len(accounts) == 0 {
			return NotFound
		}
		baseline := new(big.Int)
		for _, a := range accounts {
			if a.Currency != f.Currency {
				return Invalid("Selected account currency must match the report currency.")
			}
			view.AccountIDs = append(view.AccountIDs, a.ID)
			baseline.Add(baseline, big.NewInt(a.OpeningBalanceMinor))
		}
		seriesStart := start.AddDate(0, -(f.Months - 1), 0)
		monthly := make(map[string]dashboardMonthlyRow)
		var grouped []dashboardMonthlyRow
		// SUM(bigint) stays PostgreSQL numeric; output and intermediate derived
		// balances are range-checked explicitly rather than silently wrapping.
		if len(view.AccountIDs) > 0 {
			if err := tx.Raw(`SELECT to_char(t.occurred_at AT TIME ZONE 'Etc/GMT-5','YYYY-MM') AS month,
    COALESCE(SUM(CASE WHEN t.kind='income' AND NOT t.exclude_from_analytics THEN t.amount_minor ELSE 0 END),0)::text AS income_minor,
    COALESCE(SUM(CASE WHEN t.kind='expense' AND NOT t.exclude_from_analytics THEN t.amount_minor ELSE 0 END),0)::text AS expense_minor,
    COALESCE(SUM(CASE WHEN t.kind='income' THEN t.amount_minor ELSE -t.amount_minor END),0)::text AS ledger_change_minor
    FROM transactions t WHERE t.user_id=? AND t.currency=? AND t.account_id IN ? AND t.deleted_at IS NULL
    AND t.occurred_at>=? AND t.occurred_at<? GROUP BY month`, userID, f.Currency, view.AccountIDs, seriesStart, end).Scan(&grouped).Error; err != nil {
				return err
			}
			var before string
			if err := tx.Raw(`SELECT COALESCE(SUM(CASE WHEN kind='income' THEN amount_minor ELSE -amount_minor END),0)::text
    FROM transactions WHERE user_id=? AND currency=? AND account_id IN ? AND deleted_at IS NULL AND occurred_at<?`, userID, f.Currency, view.AccountIDs, seriesStart).Scan(&before).Error; err != nil {
				return err
			}
			previous, err := dashboardInteger(before)
			if err != nil {
				return err
			}
			baseline.Add(baseline, previous)
		}
		for _, row := range grouped {
			monthly[row.Month] = row
		}
		for i := 0; i < f.Months; i++ {
			boundary := seriesStart.AddDate(0, i, 0)
			next := boundary.AddDate(0, 1, 0)
			key := boundary.Format("2006-01")
			row, exists := monthly[key]
			if !exists {
				row = dashboardMonthlyRow{Month: key, IncomeMinor: "0", ExpenseMinor: "0", LedgerChangeMinor: "0"}
			}
			opening, err := dashboardResult(baseline)
			if err != nil {
				return err
			}
			income, err := dashboardInteger(row.IncomeMinor)
			if err != nil {
				return err
			}
			if _, err = dashboardResult(income); err != nil {
				return err
			}
			expense, err := dashboardInteger(row.ExpenseMinor)
			if err != nil {
				return err
			}
			if _, err = dashboardResult(expense); err != nil {
				return err
			}
			delta, err := dashboardInteger(row.LedgerChangeMinor)
			if err != nil {
				return err
			}
			if _, err = dashboardResult(delta); err != nil {
				return err
			}
			baseline.Add(baseline, delta)
			closing, err := dashboardResult(baseline)
			if err != nil {
				return err
			}
			net, err := dashboardResult(new(big.Int).Sub(income, expense))
			if err != nil {
				return err
			}
			view.Series = append(view.Series, AnalyticsSeriesPoint{Month: key, From: boundary.Format(time.RFC3339), To: next.Format(time.RFC3339), IncomeMinor: row.IncomeMinor, ExpenseMinor: row.ExpenseMinor, NetMinor: net, OpeningBalanceMinor: opening, ClosingBalanceMinor: closing, LedgerChangeMinor: row.LedgerChangeMinor})
		}
		current, previous := view.Series[len(view.Series)-1], view.Series[len(view.Series)-2]
		incomeDelta, err := dashboardDifference(current.IncomeMinor, previous.IncomeMinor)
		if err != nil {
			return err
		}
		expenseDelta, err := dashboardDifference(current.ExpenseMinor, previous.ExpenseMinor)
		if err != nil {
			return err
		}
		netDelta, err := dashboardDifference(current.NetMinor, previous.NetMinor)
		if err != nil {
			return err
		}
		view.Summary = AnalyticsComparison{IncomeMinor: current.IncomeMinor, ExpenseMinor: current.ExpenseMinor, NetMinor: current.NetMinor, PreviousIncomeMinor: previous.IncomeMinor, PreviousExpenseMinor: previous.ExpenseMinor, PreviousNetMinor: previous.NetMinor, IncomeDeltaMinor: incomeDelta, ExpenseDeltaMinor: expenseDelta, NetDeltaMinor: netDelta}
		var categories []dashboardCategoryRow
		if len(view.AccountIDs) > 0 {
			if err := tx.Raw(`SELECT to_char(t.occurred_at AT TIME ZONE 'Etc/GMT-5','YYYY-MM') AS month,t.kind,t.category_id,
    COALESCE(co.name,c.name,'Без категории') AS name,c.parent_id,COALESCE(po.name,p.name,'') AS parent_name,SUM(t.amount_minor)::text AS amount_minor
    FROM transactions t LEFT JOIN categories c ON c.id=t.category_id LEFT JOIN categories p ON p.id=c.parent_id
    LEFT JOIN category_overrides co ON co.category_id=c.id AND co.user_id=t.user_id
    LEFT JOIN category_overrides po ON po.category_id=p.id AND po.user_id=t.user_id
    WHERE t.user_id=? AND t.currency=? AND t.account_id IN ? AND t.deleted_at IS NULL AND NOT t.exclude_from_analytics
    AND t.occurred_at>=? AND t.occurred_at<? GROUP BY month,t.kind,t.category_id,c.name,co.name,c.parent_id,p.name,po.name`, userID, f.Currency, view.AccountIDs, start.AddDate(0, -1, 0), end).Scan(&categories).Error; err != nil {
				return err
			}
		}
		rowsCurrent, rowsPrevious := make([]analyticsRow, 0), make([]analyticsRow, 0)
		for _, r := range categories {
			row := analyticsRow{Kind: r.Kind, CategoryID: r.CategoryID, Name: r.Name, ParentID: r.ParentID, ParentName: r.ParentName, AmountMinor: r.AmountMinor}
			if r.Month == f.Month {
				rowsCurrent = append(rowsCurrent, row)
			} else {
				rowsPrevious = append(rowsPrevious, row)
			}
		}
		currentCategories, err := buildAnalytics(f.Month, f.Currency, "UTC+05:00", rowsCurrent)
		if err != nil {
			return err
		}
		previousCategories, err := buildAnalytics(start.AddDate(0, -1, 0).Format("2006-01"), f.Currency, "UTC+05:00", rowsPrevious)
		if err != nil {
			return err
		}
		if currentCategories.IncomeMinor != view.Summary.IncomeMinor || currentCategories.ExpenseMinor != view.Summary.ExpenseMinor || previousCategories.IncomeMinor != view.Summary.PreviousIncomeMinor || previousCategories.ExpenseMinor != view.Summary.PreviousExpenseMinor {
			return fmt.Errorf("analytics grouped totals disagree")
		}
		view.Income, err = compareAnalyticsBuckets(currentCategories.Income, previousCategories.Income)
		if err != nil {
			return err
		}
		view.Expenses, err = compareAnalyticsBuckets(currentCategories.Expenses, previousCategories.Expenses)
		return err
	}, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	return view, err
}

func compareAnalyticsBuckets(current, previous []AnalyticsBucket) ([]AnalyticsComparisonBucket, error) {
	prior := map[string]AnalyticsBucket{}
	now := map[string]AnalyticsBucket{}
	names := map[string]string{}
	for _, b := range previous {
		key := categorySortID(b.CategoryID)
		prior[key] = b
		names[key] = b.Name
	}
	for _, b := range current {
		key := categorySortID(b.CategoryID)
		now[key] = b
		names[key] = b.Name
	}
	out := make([]AnalyticsComparisonBucket, 0, len(names))
	for key, name := range names {
		cur, exists := now[key]
		old, oldExists := prior[key]
		amount, previousAmount := "0", "0"
		var id *string
		if exists {
			amount, id = cur.AmountMinor, cur.CategoryID
		} else {
			id = old.CategoryID
		}
		if oldExists {
			previousAmount = old.AmountMinor
		}
		children, err := compareAnalyticsBuckets(cur.Children, old.Children)
		if err != nil {
			return nil, err
		}
		direct, err := dashboardInteger(amount)
		if err != nil {
			return nil, err
		}
		for _, child := range children {
			childAmount, err := dashboardInteger(child.AmountMinor)
			if err != nil {
				return nil, err
			}
			direct.Sub(direct, childAmount)
		}
		if direct.Sign() < 0 {
			return nil, analyticsOverflow()
		}
		directText, err := dashboardResult(direct)
		if err != nil {
			return nil, err
		}
		delta, err := dashboardDifference(amount, previousAmount)
		if err != nil {
			return nil, err
		}
		out = append(out, AnalyticsComparisonBucket{CategoryID: id, Name: name, AmountMinor: amount, DirectAmountMinor: directText, PreviousAmountMinor: previousAmount, DeltaMinor: delta, Children: children})
	}
	sort.Slice(out, func(i, j int) bool {
		a, _ := strconv.ParseInt(out[i].AmountMinor, 10, 64)
		b, _ := strconv.ParseInt(out[j].AmountMinor, 10, 64)
		if a != b {
			return a > b
		}
		if out[i].Name != out[j].Name {
			return out[i].Name < out[j].Name
		}
		return categorySortID(out[i].CategoryID) < categorySortID(out[j].CategoryID)
	})
	return out, nil
}
