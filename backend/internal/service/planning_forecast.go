package service

import (
	"context"
	"math/big"
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/fx"
	"gorm.io/gorm"
)

type ForecastAccount struct {
	ID                    string  `json:"id"`
	Name                  string  `json:"name"`
	Currency              string  `json:"currency"`
	BalanceMinor          string  `json:"balance_minor"`
	ConvertedBalanceMinor *string `json:"converted_balance_minor"`
}
type ForecastObligation struct {
	OccurrenceView
	ConvertedAmountMinor *string `json:"converted_amount_minor"`
}
type ForecastView struct {
	AsOf              string               `json:"as_of"`
	Until             string               `json:"until"`
	HorizonReason     string               `json:"horizon_reason"`
	BaseCurrency      string               `json:"base_currency"`
	Complete          bool                 `json:"complete"`
	Stale             bool                 `json:"stale"`
	FetchedAt         *time.Time           `json:"fetched_at"`
	MissingCurrencies []string             `json:"missing_currencies"`
	Accounts          []ForecastAccount    `json:"accounts"`
	Obligations       []ForecastObligation `json:"obligations"`
	BalanceMinor      *string              `json:"balance_minor"`
	ReserveMinor      string               `json:"reserve_minor"`
	MandatoryMinor    *string              `json:"mandatory_minor"`
	FreeMinor         *string              `json:"free_minor"`
	PerDayMinor       *string              `json:"per_day_minor"`
	Days              int                  `json:"days"`
	OverdueFrom       string               `json:"overdue_from"`
	TimelineTruncated bool                 `json:"timeline_truncated"`
}

func (s *Service) Forecast(ctx context.Context, userID, untilRaw string) (ForecastView, error) {
	today := planningToday(s.planningNow())
	maxUntil := today.AddDate(0, 0, 365)
	until := time.Date(today.Year(), today.Month()+1, 0, 0, 0, 0, 0, time.UTC)
	reason := "month_end"
	if untilRaw != "" {
		var err error
		until, err = planningDate(untilRaw)
		if err != nil {
			return ForecastView{}, err
		}
		if until.Before(today) || until.After(maxUntil) {
			return ForecastView{}, Invalid("Forecast until must be between today and 365 days ahead.")
		}
		reason = "custom"
	}
	var planning PlanningView
	var accounts []AccountView
	err := s.DB.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := lockLedger(tx, userID); err != nil {
			return err
		}
		var err error
		planning, err = s.planningView(tx, userID, today, maxUntil)
		if err != nil {
			return err
		}
		local := *s
		local.DB = tx
		accounts, err = local.Accounts(ctx, userID)
		return err
	})
	if err != nil {
		return ForecastView{}, err
	}
	if untilRaw == "" {
		for _, o := range planning.Occurrences {
			if o.Kind == "income" && o.PrimaryIncome && o.Status == "pending" && o.BlockedReason == nil && o.DueDate >= today.Format(time.DateOnly) {
				d, _ := time.Parse(time.DateOnly, o.DueDate)
				until = d
				reason = "primary_income"
				break
			}
		}
	}
	settings := planning.Settings
	view := ForecastView{AsOf: today.Format(time.DateOnly), Until: until.Format(time.DateOnly), HorizonReason: reason, BaseCurrency: settings.BaseCurrency, Complete: !planning.TimelineTruncated, MissingCurrencies: make([]string, 0), Accounts: make([]ForecastAccount, 0), Obligations: make([]ForecastObligation, 0), ReserveMinor: settings.ReserveMinor, Days: int(until.Sub(today)/(24*time.Hour)) + 1, OverdueFrom: planning.OverdueFrom, TimelineTruncated: planning.TimelineTruncated}
	selected := map[string]bool{}
	for _, id := range settings.AccountIDs {
		selected[id] = true
	}
	foreign := false
	for _, a := range accounts {
		include := selected[a.ID]
		if len(settings.AccountIDs) == 0 {
			include = a.IncludeInBalance && (a.Type == "card" || a.Type == "cash")
		}
		if !include || a.Archived || (a.Type != "card" && a.Type != "cash") {
			continue
		}
		selected[a.ID] = true
		view.Accounts = append(view.Accounts, ForecastAccount{ID: a.ID, Name: a.Name, Currency: a.Currency, BalanceMinor: a.BalanceMinor})
		if a.Currency != view.BaseCurrency {
			foreign = true
		}
	}
	// Only accounts actually contributing cash can contribute a reserved payment.
	cashAccounts := map[string]bool{}
	for _, a := range view.Accounts {
		cashAccounts[a.ID] = true
	}
	for _, o := range planning.Occurrences {
		if o.Status != "pending" || o.Kind != "expense" || !o.Mandatory || o.DueDate > view.Until || !cashAccounts[o.AccountID] {
			continue
		}
		view.Obligations = append(view.Obligations, ForecastObligation{OccurrenceView: o})
		if o.Currency != view.BaseCurrency {
			foreign = true
		}
	}
	var snapshot *fx.Snapshot
	if foreign {
		snapshot, view.Stale, err = s.fxCache.Get(ctx)
		if err != nil {
			return view, err
		}
		view.FetchedAt = fetchedAt(snapshot)
	}
	missing := map[string]bool{}
	target, _ := fx.Lookup(view.BaseCurrency)
	convert := func(amount, currency string) (*string, error) {
		rate, _, ok := s.fxCache.Pair(snapshot, currency, view.BaseCurrency)
		if !ok {
			missing[currency] = true
			view.Complete = false
			return nil, nil
		}
		value, err := strconv.ParseInt(amount, 10, 64)
		if err != nil {
			return nil, conversionOverflow
		}
		source, _ := fx.Lookup(currency)
		converted, err := fx.ConvertMinor(value, source, target, rate)
		if err != nil {
			return nil, conversionOverflow
		}
		text := strconv.FormatInt(converted, 10)
		return &text, nil
	}
	balance, mandatory := new(big.Int), new(big.Int)
	for i := range view.Accounts {
		item := &view.Accounts[i]
		item.ConvertedBalanceMinor, err = convert(item.BalanceMinor, item.Currency)
		if err != nil {
			return view, err
		}
		if item.ConvertedBalanceMinor != nil {
			v, _ := new(big.Int).SetString(*item.ConvertedBalanceMinor, 10)
			balance.Add(balance, v)
		}
	}
	for i := range view.Obligations {
		item := &view.Obligations[i]
		item.ConvertedAmountMinor, err = convert(item.AmountMinor, item.Currency)
		if err != nil {
			return view, err
		}
		if item.ConvertedAmountMinor != nil {
			v, _ := new(big.Int).SetString(*item.ConvertedAmountMinor, 10)
			mandatory.Add(mandatory, v)
		}
	}
	for _, code := range fx.Codes() {
		if missing[code] {
			view.MissingCurrencies = append(view.MissingCurrencies, code)
		}
	}
	if !view.Complete {
		return view, nil
	}
	reserve, _ := new(big.Int).SetString(view.ReserveMinor, 10)
	free := new(big.Int).Sub(balance, mandatory)
	free.Sub(free, reserve)
	perDay := new(big.Int).Div(new(big.Int).Set(free), big.NewInt(int64(view.Days)))
	for _, value := range []*big.Int{balance, mandatory, free, perDay} {
		if !value.IsInt64() {
			return view, conversionOverflow
		}
	}
	balanceText, mandatoryText, freeText, perDayText := balance.String(), mandatory.String(), free.String(), perDay.String()
	view.BalanceMinor, view.MandatoryMinor, view.FreeMinor, view.PerDayMinor = &balanceText, &mandatoryText, &freeText, &perDayText
	return view, nil
}
