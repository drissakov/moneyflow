package httpapi

import (
	"github.com/drissakov/moneyflow/backend/internal/fx"
	"github.com/gin-gonic/gin"
)

func (a *API) currencies(c *gin.Context) {
	c.JSON(200, gin.H{"provider": fx.ProviderName, "currencies": fx.Currencies()})
}

func (a *API) preferences(c *gin.Context) {
	view, err := a.service.Preferences(c.Request.Context(), currentUser(c).ID)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}

func (a *API) setPreferences(c *gin.Context) {
	var req struct {
		BaseCurrency string `json:"base_currency"`
	}
	if !decode(c, &req) {
		return
	}
	view, err := a.service.SetPreferences(c.Request.Context(), currentUser(c).ID, req.BaseCurrency)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}

func (a *API) exchangeRates(c *gin.Context) {
	view, err := a.service.ExchangeRates(c.Request.Context(), currentUser(c).ID, c.Query("base"))
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}

func (a *API) convert(c *gin.Context) {
	var req struct {
		FromCurrency string `json:"from_currency"`
		ToCurrency   string `json:"to_currency"`
		AmountMinor  string `json:"amount_minor"`
	}
	if !decode(c, &req) {
		return
	}
	view, err := a.service.Convert(c.Request.Context(), req.FromCurrency, req.ToCurrency, req.AmountMinor)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}

func (a *API) portfolio(c *gin.Context) {
	view, err := a.service.Portfolio(c.Request.Context(), currentUser(c).ID, c.Query("base"))
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}
