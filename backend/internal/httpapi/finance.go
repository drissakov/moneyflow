package httpapi

import (
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
)

func (a *API) accounts(c *gin.Context) {
	views, err := a.service.Accounts(c.Request.Context(), currentUser(c).ID)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, gin.H{"accounts": views})
}

func (a *API) createAccount(c *gin.Context) {
	var req struct {
		Name                string `json:"name"`
		Currency            string `json:"currency"`
		OpeningBalanceMinor string `json:"opening_balance_minor"`
		Type                string `json:"type"`
	}
	if !decode(c, &req) {
		return
	}
	if values := c.Request.Header.Values("Idempotency-Key"); len(values) > 0 {
		key, ok := mutationKey(c)
		if !ok {
			return
		}
		body, err := a.service.CreateAccountIdempotent(c.Request.Context(), currentUser(c).ID, key, req.Name, req.Currency, req.OpeningBalanceMinor, req.Type)
		a.mutationResult(c, body, err, 201)
		return
	}
	view, err := a.service.CreateAccount(c.Request.Context(), currentUser(c).ID, req.Name, req.Currency, req.OpeningBalanceMinor, req.Type)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(201, view)
}

func (a *API) transactions(c *gin.Context) {
	id, err := service.ParseID(c.Query("account_id"))
	if err != nil {
		a.respondError(c, err)
		return
	}
	limit, ok := parseLimit(c)
	if !ok {
		return
	}
	views, err := a.service.Transactions(c.Request.Context(), currentUser(c).ID, id, limit)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, gin.H{"transactions": views})
}

func (a *API) createTransaction(c *gin.Context) {
	keys := c.Request.Header.Values("Idempotency-Key")
	if len(keys) != 1 {
		fail(c, 400, "invalid_request", "Exactly one Idempotency-Key UUID header is required.")
		return
	}
	key, err := service.ParseID(keys[0])
	if err != nil {
		a.respondError(c, err)
		return
	}
	var req struct {
		AccountID   string  `json:"account_id"`
		Kind        string  `json:"kind"`
		AmountMinor string  `json:"amount_minor"`
		Note        string  `json:"note"`
		OccurredAt  string  `json:"occurred_at"`
		CategoryID  *string `json:"category_id"`
	}
	if !decode(c, &req) {
		return
	}
	input, err := service.ValidateTransaction(req.AccountID, req.Kind, req.AmountMinor, req.Note, req.OccurredAt)
	if err != nil {
		a.respondError(c, err)
		return
	}
	input.CategoryID, err = service.ParseOptionalID(req.CategoryID)
	if err != nil {
		a.respondError(c, err)
		return
	}
	response, err := a.service.CreateTransaction(c.Request.Context(), currentUser(c).ID, key, input)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.Data(201, "application/json; charset=utf-8", response)
}
