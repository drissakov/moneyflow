package httpapi

import (
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
	"strconv"
)

func (a *API) analyticsDashboard(c *gin.Context) {
	months, err := strconv.Atoi(c.DefaultQuery("months", "6"))
	if err != nil {
		fail(c, 400, "invalid_request", "Months must be 3, 6 or 12.")
		return
	}
	view, err := a.service.AnalyticsDashboard(c.Request.Context(), currentUser(c).ID, service.AnalyticsDashboardFilter{Month: c.Query("month"), Currency: c.Query("currency"), Months: months, AccountID: c.Query("account_id")})
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}
