package httpapi

import "github.com/gin-gonic/gin"

type credentials struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

func (a *API) register(c *gin.Context) {
	var req credentials
	if !decode(c, &req) {
		return
	}
	view, err := a.service.Register(c.Request.Context(), req.Email, req.Password)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(201, view)
}

func (a *API) login(c *gin.Context) {
	var req credentials
	if !decode(c, &req) {
		return
	}
	view, err := a.service.Login(c.Request.Context(), req.Email, req.Password)
	if err != nil {
		a.respondError(c, err)
		return
	}
	c.JSON(200, view)
}

func (a *API) logout(c *gin.Context) {
	if err := a.service.Logout(c.Request.Context(), c.MustGet("token").(string)); err != nil {
		a.respondError(c, err)
		return
	}
	c.Status(204)
}
