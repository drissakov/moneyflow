package service

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math/big"
	"sort"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/fx"
	"github.com/drissakov/moneyflow/backend/internal/model"
	"gorm.io/gorm"
)

type dashboardHistoricalMovement struct {
	Month                string
	Kind                 string
	AmountMinor          int64
	Currency             string
	CategoryID           *string
	Name                 string
	ParentID             *string
	ParentName           string
	AccountID            string
	Date                 string
	ExcludeFromAnalytics bool
}

// A book is loaded outside the ledger transaction. Only the final read-only
// snapshot supplies accounts, transactions and category names to the response.
func (s *Service) historicalAnalyticsDashboard(ctx context.Context, userID string, f AnalyticsDashboardFilter, start, end time.Time) (AnalyticsDashboardView, error) {
	now := s.now().UTC().Truncate(time.Microsecond)
	today := planningToday(now)
	if f.Month > today.Format("2006-01") {
		return AnalyticsDashboardView{}, Invalid("Historical reports cannot include a future month.")
	}
	seriesStart := start.AddDate(0, -(f.Months - 1), 0)
	openingDate, closingDate := seriesStart.AddDate(0, 0, -1), end.AddDate(0, 0, -1)
	cutoff := end
	if f.Month == today.Format("2006-01") {
		closingDate = today
		cutoff = now.Add(time.Microsecond)
	}
	var book *fx.HistoricalSeries
	var stale, attemptedFX bool
	for {
		view, err := s.readHistoricalDashboard(ctx, userID, f, start, end, seriesStart, openingDate, closingDate, cutoff, now, book, stale, attemptedFX)
		if !errors.Is(err, errDashboardNeedsFX) {
			return view, err
		}
		book, stale, err = s.historicalFX.Get(ctx, openingDate, closingDate)
		if errors.Is(err, fx.ErrHistoricalUnavailable) {
			return AnalyticsDashboardView{}, ratesUnavailable
		}
		if err != nil {
			return AnalyticsDashboardView{}, err
		}
		attemptedFX = true
	}
}

func (s *Service) readHistoricalDashboard(ctx context.Context, userID string, f AnalyticsDashboardFilter, start, end, seriesStart, openingDate, closingDate, cutoff, now time.Time, book *fx.HistoricalSeries, stale, attemptedFX bool) (AnalyticsDashboardView, error) {
	today := planningToday(now)
	status := "past"
	if f.Month == today.Format("2006-01") {
		status = "current"
	}
	view := AnalyticsDashboardView{Mode: "consolidated", Month: f.Month, Currency: "KZT", Timezone: "UTC+05:00", Months: f.Months, AccountIDs: make([]string, 0), From: start.Format(time.RFC3339), To: end.Format(time.RFC3339), AsOf: now, Today: today.Format(time.DateOnly), PeriodStatus: status, Series: make([]AnalyticsSeriesPoint, 0, f.Months), Income: make([]AnalyticsComparisonBucket, 0), Expenses: make([]AnalyticsComparisonBucket, 0)}
	err := s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var accounts []model.Account
		if err := tx.Where("user_id=?", userID).Select("id,currency,opening_balance_minor").Order("id").Find(&accounts).Error; err != nil {
			return err
		}
		foreign := false
		for _, account := range accounts {
			view.AccountIDs = append(view.AccountIDs, account.ID)
			foreign = foreign || account.Currency != "KZT"
		}
		if foreign && !attemptedFX {
			return errDashboardNeedsFX
		}
		view.Conversion = &AnalyticsDashboardConversion{Policy: "historical", Provider: fx.ProviderName, Rates: make([]AnalyticsDashboardRate, 0), FromDate: openingDate.Format(time.DateOnly), ToDate: closingDate.Format(time.DateOnly), MaxAgeDays: fx.MaxPreviousDays}
		if foreign {
			if book == nil {
				return ratesUnavailable
			}
			fetched := book.FetchedAt
			view.Conversion.FetchedAt, view.Conversion.Stale = &fetched, stale
		}
		used := make(map[string]AnalyticsDashboardRate)
		rateFor := func(currency string, date time.Time) (*big.Rat, error) {
			rate, sourceDate, targetDate, ok := book.Pair(currency, "KZT", date)
			if !ok {
				return nil, ratesUnavailable
			}
			if currency != "KZT" {
				if targetDate == nil {
					return nil, ratesUnavailable
				}
				marketDate := *targetDate
				if sourceDate != nil && *sourceDate < marketDate {
					marketDate = *sourceDate
				}
				key := date.Format(time.DateOnly) + ":" + currency
				used[key] = AnalyticsDashboardRate{Currency: currency, Rate: fx.DecimalString(rate), RateDate: &marketDate, ValuationDate: date.Format(time.DateOnly), SourceRateDate: sourceDate, TargetRateDate: targetDate}
			}
			return rate, nil
		}
		balances := make(map[string]*big.Int, len(accounts))
		for _, account := range accounts {
			balances[account.ID] = big.NewInt(account.OpeningBalanceMinor)
		}
		monthlyNative := make(map[string]map[string]*big.Int, f.Months)
		monthly := make(map[string]*dashboardConvertedMonth, f.Months)
		convertedLedger := make(map[string]*big.Int, f.Months)
		for i := 0; i < f.Months; i++ {
			key := seriesStart.AddDate(0, i, 0).Format("2006-01")
			monthly[key] = &dashboardConvertedMonth{new(big.Int), new(big.Int)}
			monthlyNative[key], convertedLedger[key] = make(map[string]*big.Int), new(big.Int)
		}
		categories := make(map[string]*dashboardConvertedCategory)
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
			// Read every movement, including excluded transfer legs. Convert once:
			// this amount drives both eligible facts and signed cash movement.
			rows, err := tx.Raw(`SELECT t.account_id,to_char(t.occurred_at AT TIME ZONE 'Etc/GMT-5','YYYY-MM') AS month,
 to_char(t.occurred_at AT TIME ZONE 'Etc/GMT-5','YYYY-MM-DD') AS date,t.kind,t.amount_minor,t.currency,t.category_id,t.exclude_from_analytics,
 COALESCE(co.name,c.name,'Без категории') AS name,c.parent_id,COALESCE(po.name,p.name,'') AS parent_name
 FROM transactions t LEFT JOIN categories c ON c.id=t.category_id LEFT JOIN categories p ON p.id=c.parent_id
 LEFT JOIN category_overrides co ON co.category_id=c.id AND co.user_id=t.user_id
 LEFT JOIN category_overrides po ON po.category_id=p.id AND po.user_id=t.user_id
 WHERE t.user_id=? AND t.account_id IN ? AND t.deleted_at IS NULL AND t.occurred_at>=? AND t.occurred_at<?`, userID, view.AccountIDs, seriesStart, cutoff).Rows()
			if err != nil {
				return err
			}
			defer rows.Close()
			for rows.Next() {
				var fact dashboardHistoricalMovement
				if err := tx.ScanRows(rows, &fact); err != nil {
					return err
				}
				date, err := time.Parse(time.DateOnly, fact.Date)
				if err != nil {
					return fmt.Errorf("invalid historical ledger date: %w", err)
				}
				rate, err := rateFor(fact.Currency, date)
				if err != nil {
					return err
				}
				amount, err := dashboardConvertMinor(big.NewInt(fact.AmountMinor), fact.Currency, rate)
				if err != nil {
					return err
				}
				signedNative, signedConverted := big.NewInt(fact.AmountMinor), big.NewInt(amount)
				if fact.Kind != "income" {
					signedNative.Neg(signedNative)
					signedConverted.Neg(signedConverted)
				}
				if monthlyNative[fact.Month][fact.AccountID] == nil {
					monthlyNative[fact.Month][fact.AccountID] = new(big.Int)
				}
				monthlyNative[fact.Month][fact.AccountID].Add(monthlyNative[fact.Month][fact.AccountID], signedNative)
				convertedLedger[fact.Month].Add(convertedLedger[fact.Month], signedConverted)
				if fact.ExcludeFromAnalytics {
					continue
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
		balanceAt := func(date time.Time) (string, error) {
			rates := make(map[string]*big.Rat)
			for _, account := range accounts {
				if rates[account.Currency] != nil {
					continue
				}
				rate, err := rateFor(account.Currency, date)
				if err != nil {
					return "", err
				}
				rates[account.Currency] = rate
			}
			return dashboardConvertedBalance(accounts, balances, rates)
		}
		opening, err := balanceAt(openingDate)
		if err != nil {
			return err
		}
		previousDate := openingDate
		for i := 0; i < f.Months; i++ {
			boundary := seriesStart.AddDate(0, i, 0)
			key := boundary.Format("2006-01")
			valuationDate := boundary.AddDate(0, 1, -1)
			if i == f.Months-1 {
				valuationDate = closingDate
			}
			for accountID, delta := range monthlyNative[key] {
				balances[accountID].Add(balances[accountID], delta)
			}
			closing, err := balanceAt(valuationDate)
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
			movement, err := dashboardResult(convertedLedger[key])
			if err != nil {
				return err
			}
			revaluation, err := dashboardDifference(change, movement)
			if err != nil {
				return err
			}
			view.Series = append(view.Series, AnalyticsSeriesPoint{Month: key, From: boundary.Format(time.RFC3339), To: boundary.AddDate(0, 1, 0).Format(time.RFC3339), IncomeMinor: income, ExpenseMinor: expense, NetMinor: net, OpeningBalanceMinor: opening, ClosingBalanceMinor: closing, LedgerChangeMinor: change, OpeningValuationDate: previousDate.Format(time.DateOnly), ValuationDate: valuationDate.Format(time.DateOnly), ConvertedLedgerChangeMinor: movement, FXRevaluationMinor: revaluation})
			opening, previousDate = closing, valuationDate
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
			return fmt.Errorf("historical analytics grouped totals disagree")
		}
		view.Income, err = compareAnalyticsBuckets(cur.Income, prev.Income)
		if err != nil {
			return err
		}
		view.Expenses, err = compareAnalyticsBuckets(cur.Expenses, prev.Expenses)
		if err != nil {
			return err
		}
		keys := make([]string, 0, len(used))
		for key := range used {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			view.Conversion.Rates = append(view.Conversion.Rates, used[key])
		}
		return nil
	}, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	return view, err
}
