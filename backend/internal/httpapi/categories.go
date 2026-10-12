package httpapi

import (
	"encoding/json"

	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
)

func (a *API) categories(c *gin.Context) {
	views, err := a.service.Categories(c.Request.Context(), currentUser(c).ID)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, gin.H{"categories": views})
}

func (a *API) createCategory(c *gin.Context) {
	var req struct {
		Kind     string  `json:"kind"`
		Name     string  `json:"name"`
		ParentID *string `json:"parent_id"`
	}
	if !decode(c, &req) {
		return
	}
	view, err := a.service.CreateCategory(c.Request.Context(), currentUser(c).ID, req.Kind, req.Name, req.ParentID)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(201, view)
}

func (a *API) setTransactionCategory(c *gin.Context) {
	var req struct {
		CategoryID json.RawMessage `json:"category_id"`
	}
	if !decode(c, &req) {
		return
	}
	var categoryID *string
	if len(req.CategoryID) > 0 && json.Unmarshal(req.CategoryID, &categoryID) != nil {
		a.respondError(c, service.Invalid("Category_id must be a UUID string or null."))
		return
	}
	view, err := a.service.SetTransactionCategory(c.Request.Context(), currentUser(c).ID, c.Param("id"), categoryID)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}

func (a *API) analytics(c *gin.Context) {
	view, err := a.service.Analytics(c.Request.Context(), currentUser(c).ID, c.Query("month"), c.Query("currency"), c.DefaultQuery("timezone", "UTC"))
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}
