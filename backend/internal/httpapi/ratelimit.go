package httpapi

import (
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type rateWindow struct {
	Until time.Time
	Count int
}

// A bounded, in-process limiter fits this single-instance release. Multiple API
// replicas would require a shared limiter (or an edge rate limiting service).
type authLimiter struct {
	mu      sync.Mutex
	windows map[string]rateWindow
}

func newAuthLimiter() *authLimiter { return &authLimiter{windows: make(map[string]rateWindow)} }

func (l *authLimiter) allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	window, exists := l.windows[key]
	if exists && window.Until.After(now) {
		if window.Count >= 20 {
			return false
		}
		window.Count++
		l.windows[key] = window
		return true
	}
	if len(l.windows) >= 4096 {
		for ip, item := range l.windows {
			if !item.Until.After(now) {
				delete(l.windows, ip)
			}
		}
		if len(l.windows) >= 4096 {
			return false
		}
	}
	l.windows[key] = rateWindow{Until: now.Add(time.Minute), Count: 1}
	return true
}

func (l *authLimiter) middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !l.allow(c.ClientIP(), time.Now()) {
			c.Header("Retry-After", "60")
			fail(c, 429, "rate_limited", "Too many authentication attempts. Please retry in one minute.")
			return
		}
		c.Next()
	}
}
