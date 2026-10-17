package service

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"sort"
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/fx"
	"github.com/drissakov/moneyflow/backend/internal/model"
	"gorm.io/gorm"
)

type AnalyticsDashboardRate struct {
	Currency       string  `json:"currency"`
	Rate           string  `json:"rate"`
	RateDate       *string `json:"rate_date"`
	ValuationDate  string  `json:"valuation_date,omitempty"`
	SourceRateDate *string `json:"source_rate_date,omitempty"`
	TargetRateDate *string `json:"target_rate_date,omitempty"`
}

// Historical USD pairs expose an explicit null for the mathematical anchor's
// observation date, without changing the response shape of older snapshots.
func (r AnalyticsDashboardRate) MarshalJSON() ([]byte, error) {
	type rateAlias AnalyticsDashboardRate
	if r.ValuationDate == "" {
		return json.Marshal(rateAlias(r))
	}
	return json.Marshal(struct {
		rateAlias
		SourceRateDate *string `json:"source_rate_date"`
	}{rateAlias(r), r.SourceRateDate})
}

type AnalyticsDashboardConversion struct {
	Policy     string                   `json:"policy"`
	Provider   string                   `json:"provider"`
	FetchedAt  *time.Time               `json:"fetched_at"`
	Stale      bool                     `json:"stale"`
	Rates      []AnalyticsDashboardRate `json:"rates"`
	FromDate   string                   `json:"from_date,omitempty"`
	ToDate     string                   `json:"to_date,omitempty"`
	MaxAgeDays int                      `json:"max_age_days,omitempty"`
}

var errDashboardNeedsFX = errors.New("dashboard needs an FX snapshot outside its ledger transaction")

type dashboardAccountChange struct {
	AccountID         string
	Month             string
	LedgerChangeMinor string
}

type dashboardFact struct {
	Month       string
	Kind        string
	AmountMinor int64
	Currency    string
	CategoryID  *string
	Name        string
	ParentID    *string
	ParentName  string
}

type dashboardConvertedMonth struct {
	income, expense *big.Int
}

type dashboardConvertedCategory struct {
	row    analyticsRow
	amount *big.Int
}

// Discovery can request one retry, but no ledger reads from the first attempt
// escape it. The resulting accounts, balances, facts and category names all
// belong to the final repeatable-read snapshot; FX cache writes never join it.
func (s *Service) consolidatedAnalyticsDashboard(ctx context.Context, userID string, f AnalyticsDashboardFilter, start, end time.Time) (AnalyticsDashboardView, error) {
	var snapshot *fx.Snapshot
	var stale, attemptedFX bool
	for {
		view, err := s.readConsolidatedDashboard(ctx, userID, f, start, end, snapshot, stale, attemptedFX)
		if !errors.Is(err, errDashboardNeedsFX) {
			return view, err
		}
		snapshot, stale, err = s.fxCache.Get(ctx)
		if err != nil {
			return AnalyticsDashboardView{}, err
		}
		attemptedFX = true
	}
}

func (s *Service) readConsolidatedDashboard(ctx context.Context, userID string, f AnalyticsDashboardFilter, start, end time.Time, snapshot *fx.Snapshot, stale, attemptedFX bool) (AnalyticsDashboardView, error) {
	now := s.now().UTC().Truncate(time.Microsecond)
	today := planningToday(now)
	status := "current"
	if f.Month < today.Format("2006-01") {
		status = "past"
	} else if f.Month > today.Format("2006-01") {
		status = "future"
	}
	view := AnalyticsDashboardView{Mode: "consolidated", Month: f.Month, Currency: "KZT", Timezone: "UTC+05:00", Months: f.Months, AccountIDs: make([]string, 0), From: start.Format(time.RFC3339), To: end.Format(time.RFC3339), AsOf: now, Today: today.Format(time.DateOnly), PeriodStatus: status, Series: make([]AnalyticsSeriesPoint, 0, f.Months), Income: make([]AnalyticsComparisonBucket, 0), Expenses: make([]AnalyticsComparisonBucket, 0)}
	err := s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var accounts []model.Account
		if err := tx.Where("user_id=?", userID).Select("id,currency,opening_balance_minor").Order("id").Find(&accounts).Error; err != nil {
			return err
		}
		currencies := make(map[string]bool)
		foreign := false
		for _, a := range accounts {
			currencies[a.Currency] = true
			foreign = foreign || a.Currency != "KZT"
			view.AccountIDs = append(view.AccountIDs, a.ID)
		}
		if foreign && !attemptedFX {
			return errDashboardNeedsFX
		}
		view.Conversion = &AnalyticsDashboardConversion{Policy: "latest_snapshot", Provider: fx.ProviderName, Rates: make([]AnalyticsDashboardRate, 0, len(currencies))}
		if foreign {
			view.Conversion.FetchedAt, view.Conversion.Stale = fetchedAt(snapshot), stale
		}
		codes := make([]string, 0, len(currencies))
		for code := range currencies {
			codes = append(codes, code)
		}
		sort.Strings(codes)
		rates := make(map[string]*big.Rat, len(codes))
		for _, code := range codes {
			rate, date, ok := s.fxCache.Pair(snapshot, code, "KZT")
			if !ok {
				return ratesUnavailable
			}
			rates[code] = rate
			view.Conversion.Rates = append(view.Conversion.Rates, AnalyticsDashboardRate{Currency: code, Rate: fx.DecimalString(rate), RateDate: date})
		}
		seriesStart := start.AddDate(0, -(f.Months - 1), 0)
		balances := make(map[string]*big.Int, len(accounts))
		for _, a := range accounts {
			balances[a.ID] = big.NewInt(a.OpeningBalanceMinor)
		}
		monthlyNative := make(map[string]map[string]*big.Int)
		if len(accounts) > 0 {
			var before []dashboardAccountChange
			if err := tx.Raw(`SELECT account_id,SUM(CASE WHEN kind='income' THEN amount_minor ELSE -amount_minor END)::text AS ledger_change_minor
    FROM transactions WHERE user_id=? AND account_id IN ? AND deleted_at IS NULL AND occurred_at<? GROUP BY account_id`, userID, view.AccountIDs, seriesStart).Scan(&before).Error; err != nil {
				return err
			}
			for _, row := range before {
				amount, err := dashboardInteger(row.LedgerChangeMinor)
				if err != nil {
					return err
				}
				balances[row.AccountID].Add(balances[row.AccountID], amount)
			}
			var changes []dashboardAccountChange
			if err := tx.Raw(`SELECT account_id,to_char(occurred_at AT TIME ZONE 'Etc/GMT-5','YYYY-MM') AS month,
    SUM(CASE WHEN kind='income' THEN amount_minor ELSE -amount_minor END)::text AS ledger_change_minor
    FROM transactions WHERE user_id=? AND account_id IN ? AND deleted_at IS NULL AND occurred_at>=? AND occurred_at<? GROUP BY account_id,month`, userID, view.AccountIDs, seriesStart, end).Scan(&changes).Error; err != nil {
				return err
			}
			for _, row := range changes {
				amount, err := dashboardInteger(row.LedgerChangeMinor)
				if err != nil {
					return err
				}
				if monthlyNative[row.Month] == nil {
					monthlyNative[row.Month] = make(map[string]*big.Int)
				}
				monthlyNative[row.Month][row.AccountID] = amount
			}
		}
		monthly := make(map[string]*dashboardConvertedMonth, f.Months)
		categories := make(map[string]*dashboardConvertedCategory)
		for i := 0; i < f.Months; i++ {
			monthly[seriesStart.AddDate(0, i, 0).Format("2006-01")] = &dashboardConvertedMonth{new(big.Int), new(big.Int)}
		}
		// Stream individual facts rather than rounding SQL SUMs. The same rounded
		// minor amount feeds both its monthly total and its category exactly once.
		if len(accounts) > 0 {
			rows, err := tx.Raw(`SELECT to_char(t.occurred_at AT TIME ZONE 'Etc/GMT-5','YYYY-MM') AS month,t.kind,t.amount_minor,t.currency,t.category_id,
    COALESCE(co.name,c.name,'Без категории') AS name,c.parent_id,COALESCE(po.name,p.name,'') AS parent_name
    FROM transactions t LEFT JOIN categories c ON c.id=t.category_id LEFT JOIN categories p ON p.id=c.parent_id
    LEFT JOIN category_overrides co ON co.category_id=c.id AND co.user_id=t.user_id
    LEFT JOIN category_overrides po ON po.category_id=p.id AND po.user_id=t.user_id
    WHERE t.user_id=? AND t.account_id IN ? AND t.deleted_at IS NULL AND NOT t.exclude_from_analytics
    AND t.occurred_at>=? AND t.occurred_at<?`, userID, view.AccountIDs, seriesStart, end).Rows()
			if err != nil {
				return err
			}
			defer rows.Close()
			for rows.Next() {
				var fact dashboardFact
				if err := tx.ScanRows(rows, &fact); err != nil {
					return err
				}
				amount, err := dashboardConvertMinor(big.NewInt(fact.AmountMinor), fact.Currency, rates[fact.Currency])
				if err != nil {
					return err
				}
				month := monthly[fact.Month]
				if fact.Kind == "income" {
					month.income.Add(month.income, big.NewInt(amount))
				} else {
					month.expense.Add(month.expense, big.NewInt(amount))
				}
				if fact.Month >= start.AddDate(0, -1, 0).Format("2006-01") {
					key := fact.Month + ":" + fact.Kind + ":" + categorySortID(fact.CategoryID)
					group := categories[key]
					if group == nil {
						group = &dashboardConvertedCategory{row: analyticsRow{Kind: fact.Kind, CategoryID: fact.CategoryID, Name: fact.Name, ParentID: fact.ParentID, ParentName: fact.ParentName}, amount: new(big.Int)}
						categories[key] = group
					}
					group.amount.Add(group.amount, big.NewInt(amount))
				}
			}
			if err := rows.Err(); err != nil {
				return err
			}
			if err := rows.Close(); err != nil {
				return err
			}
		}
		opening, err := dashboardConvertedBalance(accounts, balances, rates)
		if err != nil {
			return err
		}
		for i := 0; i < f.Months; i++ {
			boundary := seriesStart.AddDate(0, i, 0)
			key := boundary.Format("2006-01")
			for accountID, delta := range monthlyNative[key] {
				balances[accountID].Add(balances[accountID], delta)
			}
			closing, err := dashboardConvertedBalance(accounts, balances, rates)
			if err != nil {
				return err
			}
			income, err := dashboardResult(monthly[key].income)
			if err != nil {
				return err
			}
			expense, err := dashboardResult(monthly[key].expense)
			if err != nil {
				return err
			}
			net, err := dashboardDifference(income, expense)
			if err != nil {
				return err
			}
			change, err := dashboardDifference(closing, opening)
			if err != nil {
				return err
			}
			view.Series = append(view.Series, AnalyticsSeriesPoint{Month: key, From: boundary.Format(time.RFC3339), To: boundary.AddDate(0, 1, 0).Format(time.RFC3339), IncomeMinor: income, ExpenseMinor: expense, NetMinor: net, OpeningBalanceMinor: opening, ClosingBalanceMinor: closing, LedgerChangeMinor: change})
			opening = closing
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
		currentRows, previousRows := make([]analyticsRow, 0), make([]analyticsRow, 0)
		for key, group := range categories {
			amount, err := dashboardResult(group.amount)
			if err != nil {
				return err
			}
			row := group.row
			row.AmountMinor = amount
			if key[:7] == f.Month {
				currentRows = append(currentRows, row)
			} else {
				previousRows = append(previousRows, row)
			}
		}
		cur, err := buildConvertedAnalytics(currentRows)
		if err != nil {
			return err
		}
		prev, err := buildConvertedAnalytics(previousRows)
		if err != nil {
			return err
		}
		if cur.IncomeMinor != view.Summary.IncomeMinor || cur.ExpenseMinor != view.Summary.ExpenseMinor || prev.IncomeMinor != view.Summary.PreviousIncomeMinor || prev.ExpenseMinor != view.Summary.PreviousExpenseMinor {
			return fmt.Errorf("consolidated analytics grouped totals disagree")
		}
		view.Income, err = compareAnalyticsBuckets(cur.Income, prev.Income)
		if err != nil {
			return err
		}
		view.Expenses, err = compareAnalyticsBuckets(cur.Expenses, prev.Expenses)
		return err
	}, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	return view, err
}

func dashboardConvertMinor(amount *big.Int, currency string, rate *big.Rat) (int64, error) {
	if !amount.IsInt64() {
		return 0, analyticsOverflow()
	}
	source, ok := fx.Lookup(currency)
	if !ok || rate == nil {
		return 0, ratesUnavailable
	}
	target, _ := fx.Lookup("KZT")
	converted, err := fx.ConvertMinor(amount.Int64(), source, target, rate)
	if err != nil {
		return 0, analyticsOverflow()
	}
	return converted, nil
}

func dashboardConvertedBalance(accounts []model.Account, balances map[string]*big.Int, rates map[string]*big.Rat) (string, error) {
	total := new(big.Int)
	for _, a := range accounts {
		converted, err := dashboardConvertMinor(balances[a.ID], a.Currency, rates[a.Currency])
		if err != nil {
			return "", err
		}
		total.Add(total, big.NewInt(converted))
	}
	return dashboardResult(total)
}

// Native facts are strictly positive, while a valid conversion can round to
// zero. Preserve those categories so a user's small purchases remain visible.
func buildConvertedAnalytics(rows []analyticsRow) (AnalyticsView, error) {
	result := AnalyticsView{Income: make([]AnalyticsBucket, 0), Expenses: make([]AnalyticsBucket, 0)}
	groups := map[string]*accumulatedBucket{}
	var income, expense int64
	for _, row := range rows {
		amount, err := strconv.ParseInt(row.AmountMinor, 10, 64)
		if err != nil || amount < 0 {
			return result, analyticsOverflow()
		}
		if row.Kind == "income" {
			income, err = addAnalyticsAmount(income, amount)
		} else {
			expense, err = addAnalyticsAmount(expense, amount)
		}
		if err != nil {
			return result, err
		}
		rootID, name := row.CategoryID, row.Name
		if row.ParentID != nil {
			rootID, name = row.ParentID, row.ParentName
		}
		key := row.Kind + ":" + categorySortID(rootID)
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
			root.view.Children = append(root.view.Children, AnalyticsBucket{CategoryID: row.CategoryID, Name: row.Name, AmountMinor: row.AmountMinor, Children: make([]AnalyticsBucket, 0)})
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
	result.IncomeMinor, result.ExpenseMinor = strconv.FormatInt(income, 10), strconv.FormatInt(expense, 10)
	return result, nil
}
