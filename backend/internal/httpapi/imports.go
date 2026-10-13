package httpapi

import (
	"strconv"

	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
)

const importMaxBodyBytes = 2 * 1024 * 1024

func (a *API) cleanupImports() gin.HandlerFunc {
	return func(c *gin.Context) {
		if err := a.service.CleanupExpiredImports(c.Request.Context(), currentUser(c).ID); err != nil {
			a.respondError(c, err)
			return
		}
		c.Next()
	}
}

func (a *API) previewImport(c *gin.Context) {
	var input *service.ImportPreviewInput
	if !decodeWithLimit(c, &input, importMaxBodyBytes) {
		return
	}
	if input == nil {
		fail(c, 400, "invalid_request", "Preview body must be a JSON object.")
		return
	}
	view, err := a.service.PreviewImport(c.Request.Context(), currentUser(c).ID, *input)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(201, view)
}

func (a *API) importKey(c *gin.Context) (string, bool) {
	keys := c.Request.Header.Values("Idempotency-Key")
	if len(keys) != 1 {
		fail(c, 400, "invalid_request", "Exactly one Idempotency-Key UUID header is required.")
		return "", false
	}
	key, err := service.ParseID(keys[0])
	if err != nil {
		a.respondError(c, err)
		return "", false
	}
	return key, true
}

func (a *API) commitImport(c *gin.Context) {
	key, ok := a.importKey(c)
	if !ok {
		return
	}
	var input *service.ImportCommitInput
	if !decodeWithLimit(c, &input, importMaxBodyBytes) {
		return
	}
	if input == nil {
		fail(c, 400, "invalid_request", "Commit body must be a JSON object.")
		return
	}
	body, err := a.service.CommitImport(c.Request.Context(), currentUser(c).ID, c.Param("id"), key, *input)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.Data(201, "application/json; charset=utf-8", body)
}

func (a *API) undoImport(c *gin.Context) {
	key, ok := a.importKey(c)
	if !ok {
		return
	}
	var input *struct{}
	if !decodeWithLimit(c, &input, importMaxBodyBytes) {
		return
	}
	if input == nil {
		fail(c, 400, "invalid_request", "Undo body must be an empty JSON object.")
		return
	}
	body, err := a.service.UndoImport(c.Request.Context(), currentUser(c).ID, c.Param("id"), key)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.Data(200, "application/json; charset=utf-8", body)
}

func (a *API) imports(c *gin.Context) {
	limit, err := strconv.Atoi(c.DefaultQuery("limit", "20"))
	if err != nil || limit < 1 || limit > 100 {
		fail(c, 400, "invalid_request", "Limit must be between 1 and 100.")
		return
	}
	views, err := a.service.Imports(c.Request.Context(), currentUser(c).ID, limit)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, gin.H{"imports": views})
}
