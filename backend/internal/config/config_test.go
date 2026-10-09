package config

import (
	"testing"
	"time"
)

func TestConfigDefaultsAndBounds(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://unused")
	t.Setenv("PORT", "")
	t.Setenv("SESSION_TTL", "")
	t.Setenv("APP_ENV", "development")
	t.Setenv("TRUSTED_PROXIES", "")
	c, err := Load()
	if err != nil || c.Port != "8080" || c.SessionTTL != 720*time.Hour || len(c.TrustedProxies) != 0 {
		t.Fatalf("invalid default config: %#v %v", c, err)
	}
	t.Setenv("TRUSTED_PROXIES", "172.30.50.10, 10.0.0.0/24")
	if _, err := Load(); err != nil {
		t.Fatal("valid trusted proxies rejected")
	}
	for _, ttl := range []string{"0", "30s", "2161h", "bad"} {
		t.Setenv("SESSION_TTL", ttl)
		if _, err := Load(); err == nil {
			t.Fatalf("invalid TTL accepted: %q", ttl)
		}
	}
}
