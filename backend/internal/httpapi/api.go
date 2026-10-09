package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/service"
	"github.com/gin-gonic/gin"
)

const maxBodyBytes = 16 * 1024

type Options struct {
	Logger         *slog.Logger
	TrustedProxies []string
}

type API struct {
	service *service.Service
	logger  *slog.Logger
}

func New(s *service.Service, opts Options) (*gin.Engine, error) {
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	a := &API{service: s, logger: logger}
	router := gin.New()
	router.HandleMethodNotAllowed = true
	if err := router.SetTrustedProxies(opts.TrustedProxies); err != nil {
		return nil, err
	}
	router.Use(a.requestContext(), gin.CustomRecoveryWithWriter(io.Discard, func(c *gin.Context, _ any) {
		logger.Error("request panicked", "method", c.Request.Method, "path", c.FullPath())
		fail(c, 500, "internal_error", "Request could not be completed.")
	}))
	router.NoRoute(func(c *gin.Context) { fail(c, 404, "not_found", "Endpoint was not found.") })
	router.NoMethod(func(c *gin.Context) { fail(c, 405, "method_not_allowed", "Method is not allowed for this endpoint.") })
	router.GET("/healthz", func(c *gin.Context) { c.JSON(200, gin.H{"status": "ok"}) })
	router.GET("/readyz", a.ready)
	v1 := router.Group("/api/v1")
	limiter := newAuthLimiter()
	v1.POST("/auth/register", limiter.middleware(), a.register)
	v1.POST("/auth/login", limiter.middleware(), a.login)
	private := v1.Group("", a.authenticate())
	private.POST("/auth/logout", a.logout)
	private.GET("/me", func(c *gin.Context) { c.JSON(200, currentUser(c)) })
	private.GET("/accounts", a.accounts)
	private.POST("/accounts", a.createAccount)
	private.GET("/transactions", a.transactions)
	private.POST("/transactions", a.createTransaction)
	return router, nil
}

func (a *API) requestContext() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		ctx, cancel := context.WithTimeout(c.Request.Context(), 15*time.Second)
		defer cancel()
		c.Request = c.Request.WithContext(ctx)
		c.Header("X-Content-Type-Options", "nosniff")
		c.Header("Cache-Control", "no-store")
		c.Next()
		path := c.FullPath()
		if path == "" {
			path = "unmatched"
		}
		// Do not log request/response bodies, query strings, or authorization headers.
		a.logger.Info("request", "method", c.Request.Method, "path", path, "status", c.Writer.Status(), "duration_ms", time.Since(start).Milliseconds())
	}
}

func (a *API) ready(c *gin.Context) {
	db, err := a.service.DB.DB()
	if err == nil {
		ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
		defer cancel()
		err = db.PingContext(ctx)
	}
	if err != nil {
		fail(c, 503, "not_ready", "Database is not ready.")
		return
	}
	c.JSON(200, gin.H{"status": "ready"})
}

func fail(c *gin.Context, status int, code, message string) {
	c.AbortWithStatusJSON(status, gin.H{"error": gin.H{"code": code, "message": message}})
}

func (a *API) respondError(c *gin.Context, err error) {
	if domain, ok := service.AsError(err); ok {
		if domain.Status == 401 {
			c.Header("WWW-Authenticate", "Bearer")
		}
		if domain.Status == 429 {
			c.Header("Retry-After", "1")
		}
		fail(c, domain.Status, domain.Code, domain.Message)
		return
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) || c.Request.Context().Err() != nil {
		fail(c, 504, "request_timeout", "Request timed out. Please retry.")
		return
	}
	a.logger.Error("request failed", "method", c.Request.Method, "path", c.FullPath(), "error_type", fmt.Sprintf("%T", err))
	fail(c, 500, "internal_error", "Request could not be completed.")
}

func decode(c *gin.Context, dst any) bool {
	mediaType, _, err := mime.ParseMediaType(c.GetHeader("Content-Type"))
	if err != nil || mediaType != "application/json" {
		fail(c, 415, "invalid_content_type", "Content-Type must be application/json.")
		return false
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxBodyBytes)
	decoder := json.NewDecoder(c.Request.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(dst); err != nil {
		decodeError(c, err)
		return false
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		decodeError(c, err)
		return false
	}
	return true
}

func decodeError(c *gin.Context, err error) {
	var maxBytes *http.MaxBytesError
	if errors.As(err, &maxBytes) {
		fail(c, 413, "payload_too_large", "JSON body must be at most 16384 bytes.")
		return
	}
	fail(c, 400, "invalid_request", "Body must contain one valid JSON object with only documented fields.")
}

func (a *API) authenticate() gin.HandlerFunc {
	return func(c *gin.Context) {
		values := c.Request.Header.Values("Authorization")
		if len(values) != 1 {
			a.respondError(c, service.Unauthorized)
			return
		}
		parts := strings.Fields(values[0])
		if len(parts) != 2 || !strings.EqualFold(parts[0], "Bearer") {
			a.respondError(c, service.Unauthorized)
			return
		}
		user, err := a.service.Authenticate(c.Request.Context(), parts[1])
		if err != nil {
			a.respondError(c, err)
			return
		}
		c.Set("user", user)
		c.Set("token", parts[1])
		c.Next()
	}
}

func currentUser(c *gin.Context) service.UserView { return c.MustGet("user").(service.UserView) }

func parseLimit(c *gin.Context) (int, bool) {
	raw := c.DefaultQuery("limit", "50")
	limit, err := strconv.Atoi(raw)
	if err != nil || limit < 1 || limit > 100 {
		fail(c, 400, "invalid_request", "Limit must be an integer between 1 and 100.")
		return 0, false
	}
	return limit, true
}
