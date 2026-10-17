package httpapi

import (
	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
)

func (a *API) planning(c *gin.Context) {
	view, err := a.service.Planning(c.Request.Context(), currentUser(c).ID, c.Query("from"), c.Query("to"))
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}
func (a *API) forecast(c *gin.Context) {
	view, err := a.service.Forecast(c.Request.Context(), currentUser(c).ID, c.Query("until"))
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}
func (a *API) createBudget(c *gin.Context) { a.saveBudget(c, false) }
func (a *API) updateBudget(c *gin.Context) { a.saveBudget(c, true) }
func (a *API) saveBudget(c *gin.Context, update bool) {
	id := ""
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
	var input service.BudgetInput
	if !decodeLedger(c, &input) {
		return
	}
	if update && input.ExpectedVersion < 1 {
		fail(c, 400, "invalid_request", "Expected_version must be positive.")
		return
	}
	body, err := a.service.SaveBudget(c.Request.Context(), currentUser(c).ID, id, key, input)
	status := 201
	if update {
		status = 200
	}
	a.mutationResult(c, body, err, status)
}
func (a *API) createSchedule(c *gin.Context) { a.saveSchedule(c, false) }
func (a *API) updateSchedule(c *gin.Context) { a.saveSchedule(c, true) }
func (a *API) saveSchedule(c *gin.Context, update bool) {
	id := ""
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
	var input service.ScheduleInput
	if !decodeLedger(c, &input) {
		return
	}
	if update && input.ExpectedVersion < 1 {
		fail(c, 400, "invalid_request", "Expected_version must be positive.")
		return
	}
	body, err := a.service.SaveSchedule(c.Request.Context(), currentUser(c).ID, id, key, input)
	status := 201
	if update {
		status = 200
	}
	a.mutationResult(c, body, err, status)
}
func (a *API) planningSettings(c *gin.Context) {
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.PlanningSettingsInput
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.SavePlanningSettings(c.Request.Context(), currentUser(c).ID, key, input)
	a.mutationResult(c, body, err, 200)
}
func (a *API) skipOccurrence(c *gin.Context)   { a.occurrenceState(c, true) }
func (a *API) unskipOccurrence(c *gin.Context) { a.occurrenceState(c, false) }
func (a *API) occurrenceState(c *gin.Context, skip bool) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.OccurrenceInput
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.ChangeOccurrenceState(c.Request.Context(), currentUser(c).ID, id, c.Param("date"), key, input, skip)
	a.mutationResult(c, body, err, 200)
}
func (a *API) postOccurrence(c *gin.Context) {
	id, ok := entityID(c)
	if !ok {
		return
	}
	key, ok := mutationKey(c)
	if !ok {
		return
	}
	var input service.PostOccurrenceInput
	if !decodeLedger(c, &input) {
		return
	}
	body, err := a.service.PostOccurrence(c.Request.Context(), currentUser(c).ID, id, c.Param("date"), key, input)
	a.mutationResult(c, body, err, 200)
}
