package service

import (
	"context"
	"math/big"
	"strconv"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/fx"
	"github.com/drissakov/moneyflow/backend/internal/model"
)

type PreferencesView struct {
	BaseCurrency string `json:"base_currency"`
}

func (s *Service) Preferences(ctx context.Context, userID string) (PreferencesView, error) {
	var view PreferencesView
	result := s.DB.WithContext(ctx).Model(&model.User{}).Select("base_currency").Where("id = ?", userID).Scan(&view)
	if result.Error != nil {
		return view, result.Error
	}
	if result.RowsAffected == 0 {
		return view, Unauthorized
	}
	return view, nil
}

func (s *Service) SetPreferences(ctx context.Context, userID, base string) (PreferencesView, error) {
	if err := ValidateCurrency(base); err != nil {
		return PreferencesView{}, err
	}
	result := s.DB.WithContext(ctx).Model(&model.User{}).Where("id = ?", userID).Update("base_currency", base)
	if result.Error != nil {
		return PreferencesView{}, result.Error
	}
	if result.RowsAffected == 0 {
		return PreferencesView{}, Unauthorized
	}
	return PreferencesView{BaseCurrency: base}, nil
}

func (s *Service) resolveBase(ctx context.Context, userID, base string) (string, error) {
	if base == "" {
		preference, err := s.Preferences(ctx, userID)
		if err != nil {
			return "", err
		}
		base = preference.BaseCurrency
	}
	return base, ValidateCurrency(base)
}

type RateView struct {
	QuoteCurrency string  `json:"quote_currency"`
	Rate          string  `json:"rate"`
	RateDate      *string `json:"rate_date"`
}

type ExchangeRatesView struct {
	BaseCurrency      string     `json:"base_currency"`
	Provider          string     `json:"provider"`
	FetchedAt         *time.Time `json:"fetched_at"`
	Stale             bool       `json:"stale"`
	Rates             []RateView `json:"rates"`
	MissingCurrencies []string   `json:"missing_currencies"`
}

func fetchedAt(snapshot *fx.Snapshot) *time.Time {
	if snapshot == nil {
		return nil
	}
	value := snapshot.FetchedAt.UTC()
	return &value
}

func (s *Service) ExchangeRates(ctx context.Context, userID, base string) (ExchangeRatesView, error) {
	view := ExchangeRatesView{Provider: fx.ProviderName, Rates: make([]RateView, 0), MissingCurrencies: make([]string, 0)}
	var err error
	view.BaseCurrency, err = s.resolveBase(ctx, userID, base)
	if err != nil {
		return view, err
	}
	snapshot, stale, err := s.fxCache.Get(ctx)
	if err != nil {
		return view, err
	}
	view.FetchedAt, view.Stale = fetchedAt(snapshot), stale
	for _, code := range fx.Codes() {
		rate, date, ok := s.fxCache.Pair(snapshot, view.BaseCurrency, code)
		if !ok {
			view.MissingCurrencies = append(view.MissingCurrencies, code)
			continue
		}
		view.Rates = append(view.Rates, RateView{QuoteCurrency: code, Rate: fx.DecimalString(rate), RateDate: date})
	}
	return view, nil
}

type ConversionView struct {
	FromCurrency         string     `json:"from_currency"`
	ToCurrency           string     `json:"to_currency"`
	AmountMinor          string     `json:"amount_minor"`
	ConvertedAmountMinor string     `json:"converted_amount_minor"`
	Rate                 string     `json:"rate"`
	RateDate             *string    `json:"rate_date"`
	FetchedAt            *time.Time `json:"fetched_at"`
	Stale                bool       `json:"stale"`
	Provider             string     `json:"provider"`
}

var ratesUnavailable = &Error{503, "rates_unavailable", "Exchange rates for this currency pair are unavailable. Please retry later."}
var conversionOverflow = &Error{409, "conversion_overflow", "Converted amount or portfolio total exceeds the signed 64-bit range."}

func (s *Service) Convert(ctx context.Context, from, to, amount string) (ConversionView, error) {
	view := ConversionView{FromCurrency: from, ToCurrency: to, AmountMinor: amount, Provider: fx.ProviderName}
	if err := ValidateCurrency(from); err != nil {
		return view, err
	}
	if err := ValidateCurrency(to); err != nil {
		return view, err
	}
	value, err := ParseMinor(amount, false)
	if err != nil {
		return view, err
	}
	if from == to {
		view.ConvertedAmountMinor, view.Rate = amount, "1"
		return view, nil
	}
	snapshot, stale, err := s.fxCache.Get(ctx)
	if err != nil {
		return view, err
	}
	rate, date, ok := s.fxCache.Pair(snapshot, from, to)
	if !ok {
		return view, ratesUnavailable
	}
	source, _ := fx.Lookup(from)
	target, _ := fx.Lookup(to)
	converted, err := fx.ConvertMinor(value, source, target, rate)
	if err != nil {
		return view, conversionOverflow
	}
	view.ConvertedAmountMinor = strconv.FormatInt(converted, 10)
	view.Rate, view.RateDate, view.FetchedAt, view.Stale = fx.DecimalString(rate), date, fetchedAt(snapshot), stale
	return view, nil
}

type PortfolioAccountView struct {
	ID                    string  `json:"id"`
	Name                  string  `json:"name"`
	Currency              string  `json:"currency"`
	BalanceMinor          string  `json:"balance_minor"`
	ConvertedBalanceMinor *string `json:"converted_balance_minor"`
	Rate                  *string `json:"rate"`
	RateDate              *string `json:"rate_date"`
}

type PortfolioView struct {
	BaseCurrency        string                 `json:"base_currency"`
	Provider            string                 `json:"provider"`
	FetchedAt           *time.Time             `json:"fetched_at"`
	Stale               bool                   `json:"stale"`
	Complete            bool                   `json:"complete"`
	TotalBalanceMinor   *string                `json:"total_balance_minor"`
	PartialBalanceMinor string                 `json:"partial_balance_minor"`
	MissingCurrencies   []string               `json:"missing_currencies"`
	Accounts            []PortfolioAccountView `json:"accounts"`
}

func (s *Service) Portfolio(ctx context.Context, userID, base string) (PortfolioView, error) {
	view := PortfolioView{Provider: fx.ProviderName, Complete: true, MissingCurrencies: make([]string, 0), Accounts: make([]PortfolioAccountView, 0)}
	var err error
	view.BaseCurrency, err = s.resolveBase(ctx, userID, base)
	if err != nil {
		return view, err
	}
	accounts, err := s.Accounts(ctx, userID)
	if err != nil {
		return view, err
	}
	foreign := false
	for _, account := range accounts {
		if !account.IncludeInBalance {
			continue
		}
		if account.Currency != view.BaseCurrency {
			foreign = true
			break
		}
	}
	var snapshot *fx.Snapshot
	if foreign {
		var stale bool
		snapshot, stale, err = s.fxCache.Get(ctx)
		if err != nil {
			return view, err
		}
		view.FetchedAt, view.Stale = fetchedAt(snapshot), stale
	}
	missing := make(map[string]bool)
	total := new(big.Int)
	target, _ := fx.Lookup(view.BaseCurrency)
	for _, account := range accounts {
		if !account.IncludeInBalance {
			continue
		}
		item := PortfolioAccountView{ID: account.ID, Name: account.Name, Currency: account.Currency, BalanceMinor: account.BalanceMinor}
		rate, date, ok := s.fxCache.Pair(snapshot, account.Currency, view.BaseCurrency)
		if !ok {
			view.Complete = false
			missing[account.Currency] = true
			view.Accounts = append(view.Accounts, item)
			continue
		}
		balance, parseErr := strconv.ParseInt(account.BalanceMinor, 10, 64)
		if parseErr != nil {
			return view, conversionOverflow
		}
		source, _ := fx.Lookup(account.Currency)
		converted, convertErr := fx.ConvertMinor(balance, source, target, rate)
		if convertErr != nil {
			return view, conversionOverflow
		}
		convertedText, rateText := strconv.FormatInt(converted, 10), fx.DecimalString(rate)
		item.ConvertedBalanceMinor, item.Rate, item.RateDate = &convertedText, &rateText, date
		total.Add(total, big.NewInt(converted))
		view.Accounts = append(view.Accounts, item)
	}
	if !total.IsInt64() {
		return view, conversionOverflow
	}
	view.PartialBalanceMinor = total.String()
	if view.Complete {
		value := total.String()
		view.TotalBalanceMinor = &value
	}
	for _, code := range fx.Codes() {
		if missing[code] {
			view.MissingCurrencies = append(view.MissingCurrencies, code)
		}
	}
	return view, nil
}
