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
	t.Setenv("FRANKFURTER_URL", "")
	t.Setenv("FX_CACHE_TTL", "")
	t.Setenv("FX_MAX_STALE", "")
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

func TestFXConfigBounds(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://unused")
	t.Setenv("PORT", "8080")
	t.Setenv("SESSION_TTL", "720h")
	t.Setenv("APP_ENV", "test")
	t.Setenv("TRUSTED_PROXIES", "")
	t.Setenv("FRANKFURTER_URL", "")
	t.Setenv("FX_CACHE_TTL", "")
	t.Setenv("FX_MAX_STALE", "")
	config, err := Load()
	if err != nil || config.FrankfurterURL != "https://api.frankfurter.dev/v2" || config.FXCacheTTL != 6*time.Hour || config.FXMaxStale != 168*time.Hour {
		t.Fatal("FX defaults invalid")
	}
	for _, value := range []string{"0", "59s", "25h", "nonsense"} {
		t.Setenv("FX_CACHE_TTL", value)
		if _, err := Load(); err == nil {
			t.Error("invalid cache TTL accepted")
		}
	}
	t.Setenv("FX_CACHE_TTL", "6h")
	for _, value := range []string{"0", "59m", "5h", "721h", "nonsense"} {
		t.Setenv("FX_MAX_STALE", value)
		if _, err := Load(); err == nil {
			t.Error("invalid stale duration accepted")
		}
	}
	t.Setenv("FX_MAX_STALE", "168h")
	t.Setenv("FRANKFURTER_URL", "http://example.com/v2")
	if _, err := Load(); err == nil {
		t.Fatal("insecure remote Frankfurter URL accepted")
	}
}
