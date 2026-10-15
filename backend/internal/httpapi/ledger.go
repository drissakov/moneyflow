package httpapi

import (
	"bytes"
	"encoding/json"
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
	"strconv"
)

func mutationKey(c *gin.Context) (string, bool) {
	values := c.Request.Header.Values("Idempotency-Key")
	if len(values) != 1 {
		fail(c, 400, "invalid_request", "Exactly one Idempotency-Key UUID header is required.")
		return "", false
	}
	key, err := service.ParseID(values[0])
	if err != nil {
		fail(c, 400, "invalid_request", "Idempotency-Key must be a nonzero UUID.")
		return "", false
	}
	return key, true
}
func entityID(c *gin.Context) (string, bool) {
	id, err := service.ParseID(c.Param("id"))
	if err != nil {
		fail(c, 400, "invalid_request", "Invalid entity identifier.")
		return "", false
	}
	return id, true
}
func (a *API) mutationResult(c *gin.Context, body []byte, err error, status int) {
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.Data(status, "application/json; charset=utf-8", body)
}
func (a *API) updateAccount(c *gin.Context) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.UpdateAccountInput
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.UpdateAccount(c.Request.Context(), currentUser(c).ID, id, key, input)
	a.mutationResult(c, body, err, 200)
}
func (a *API) updateTransaction(c *gin.Context) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.UpdateTransactionInput
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.UpdateTransaction(c.Request.Context(), currentUser(c).ID, id, key, input)
	a.mutationResult(c, body, err, 200)
}
func (a *API) deleteTransaction(c *gin.Context)  { a.transactionState(c, false) }
func (a *API) restoreTransaction(c *gin.Context) { a.transactionState(c, true) }
func (a *API) transactionState(c *gin.Context, restore bool) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.ExpectedVersion
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.ChangeTransactionState(c.Request.Context(), currentUser(c).ID, id, key, input, restore)
	a.mutationResult(c, body, err, 200)
}
func (a *API) updateCategory(c *gin.Context) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.UpdateCategoryInput
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.UpdateCategory(c.Request.Context(), currentUser(c).ID, id, key, input)
	a.mutationResult(c, body, err, 200)
}
func (a *API) mergeCategory(c *gin.Context) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.MergeCategoryInput
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.MergeCategory(c.Request.Context(), currentUser(c).ID, id, key, input)
	a.mutationResult(c, body, err, 200)
}
func (a *API) getTransfer(c *gin.Context) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	view, err := a.service.GetTransfer(c.Request.Context(), currentUser(c).ID, id)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}
func (a *API) transfers(c *gin.Context) {
	limit, ok := parseLimit(c)
	if !ok {
		return
	}
	view, err := a.service.Transfers(c.Request.Context(), currentUser(c).ID, c.Query("cursor"), limit)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}
func (a *API) createTransfer(c *gin.Context) { a.saveTransfer(c, false) }
func (a *API) updateTransfer(c *gin.Context) { a.saveTransfer(c, true) }
func (a *API) saveTransfer(c *gin.Context, update bool) {
	var id string
	var ok bool
	if update {
		id, ok = entityID(c)
		if !ok {
			return
		}
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.TransferInput
	if !decodeLedger(c, &input) {
		return
	}
	if update && input.ExpectedVersion < 1 {
		fail(c, 400, "invalid_request", "Expected_version must be positive.")
		return
	}
	if !update && input.ExpectedVersion != 0 {
		fail(c, 400, "invalid_request", "Create transfer must omit expected_version.")
		return
	}
	status := 201
	if update {
		status = 200
	}
	body, err := a.service.SaveTransfer(c.Request.Context(), currentUser(c).ID, id, key, input)
	a.mutationResult(c, body, err, status)
}
func (a *API) deleteTransfer(c *gin.Context)  { a.transferState(c, false) }
func (a *API) restoreTransfer(c *gin.Context) { a.transferState(c, true) }
func (a *API) transferState(c *gin.Context, restore bool) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.ExpectedVersion
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.ChangeTransferState(c.Request.Context(), currentUser(c).ID, id, key, input, restore)
	a.mutationResult(c, body, err, 200)
}
func (a *API) history(c *gin.Context) {
	limit, ok := parseLimit(c)
	if !ok {
		return
	}
	includeDeleted := false
	if value, exists := c.GetQuery("include_deleted"); exists {
		parsed, err := strconv.ParseBool(value)
		if err != nil {
			fail(c, 400, "invalid_request", "Include_deleted must be boolean.")
			return
		}
		includeDeleted = parsed
	}
	view, err := a.service.History(c.Request.Context(), currentUser(c).ID, service.HistoryFilter{AccountID: c.Query("account_id"), Cursor: c.Query("cursor"), Query: c.Query("q"), From: c.Query("from"), To: c.Query("to"), CategoryID: c.Query("category_id"), Kind: c.Query("kind"), IncludeDeleted: includeDeleted, Limit: limit})
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}

// Full PUT commands must not silently turn omitted booleans into false. Unlike
// legacy POST payloads, these commands explicitly carry every editable field.
func decodeLedger(c *gin.Context, dst any) bool {
	var raw json.RawMessage
	if !decode(c, &raw) {
		return false
	}
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil || fields == nil {
		fail(c, 400, "invalid_request", "Body must be a JSON object.")
		return false
	}
	var required []string
	switch dst.(type) {
	case *service.UpdateAccountInput:
		required = []string{"expected_version", "name", "type", "opening_balance_minor", "archived", "include_in_balance", "sort_order"}
	case *service.UpdateCategoryInput:
		required = []string{"expected_version", "name", "archived", "sort_order"}
	case *service.MergeCategoryInput:
		required = []string{"expected_version", "target_category_id"}
	case *service.UpdateTransactionInput:
		required = []string{"expected_version", "account_id", "kind", "amount_minor", "note", "occurred_at", "category_id", "exclude_from_analytics"}
	case *service.ExpectedVersion:
		required = []string{"expected_version"}
	case *service.TransferInput:
		required = []string{"from_account_id", "to_account_id", "from_amount_minor", "to_amount_minor", "occurred_at"}
	}
	for _, name := range required {
		value, exists := fields[name]
		if !exists || (bytes.Equal(value, []byte("null")) && name != "category_id") {
			fail(c, 400, "invalid_request", "Missing required command field: "+name)
			return false
		}
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dst); err != nil {
		decodeError(c, err)
		return false
	}
	return true
}
