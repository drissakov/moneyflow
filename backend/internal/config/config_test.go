package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestConfigDefaultsAndBounds(t *testing.T) {
	t.Setenv("DATABASE_URL_FILE", "")
	t.Setenv("METRICS_PORT", "")
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
	t.Setenv("DATABASE_URL_FILE", "")
	t.Setenv("METRICS_PORT", "")
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

func TestMetricsListenerConfig(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://unused")
	t.Setenv("DATABASE_URL_FILE", "")
	t.Setenv("PORT", "8080")
	t.Setenv("METRICS_PORT", "9090")
	t.Setenv("APP_ENV", "test")
	c, err := Load()
	if err != nil || c.MetricsPort != "9090" {
		t.Fatalf("metrics config invalid: %v", err)
	}
	for _, port := range []string{"8080", "08080", "0", "65536", "secret-port"} {
		t.Setenv("METRICS_PORT", port)
		if _, err := Load(); err == nil {
			t.Fatalf("invalid or colliding metrics port accepted: %q", port)
		}
	}
}

func TestDatabaseURLFile(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	t.Setenv("METRICS_PORT", "")
	t.Setenv("APP_ENV", "test")
	path := filepath.Join(t.TempDir(), "database_url")
	t.Setenv("DATABASE_URL_FILE", path)
	if err := os.WriteFile(path, []byte("postgres://user:secret@db/moneyflow\n"), 0600); err != nil {
		t.Fatal(err)
	}
	c, err := Load()
	if err != nil || c.DatabaseURL != "postgres://user:secret@db/moneyflow" {
		t.Fatal("database URL file not loaded")
	}
	t.Setenv("DATABASE_URL", "postgres://another-secret")
	if _, err := Load(); err == nil || strings.Contains(err.Error(), "secret") {
		t.Fatal("ambiguous database URL sources accepted or leaked")
	}
	t.Setenv("DATABASE_URL", "")
	for _, value := range []string{"\n", strings.Repeat("x", 16*1024+1)} {
		if err := os.WriteFile(path, []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := Load(); err == nil {
			t.Fatal("empty or oversized database URL file accepted")
		}
	}
	t.Setenv("DATABASE_URL_FILE", filepath.Join(t.TempDir(), "private-secret-filename"))
	if _, err := Load(); err == nil || strings.Contains(err.Error(), "private-secret-filename") {
		t.Fatal("unreadable file accepted or path leaked")
	}
}

func TestDatabasePoolBoundsForServerless(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://unused")
	t.Setenv("DATABASE_URL_FILE", "")
	t.Setenv("APP_ENV", "test")
	t.Setenv("METRICS_PORT", "")
	t.Setenv("DATABASE_MAX_OPEN_CONNECTIONS", "4")
	t.Setenv("DATABASE_MAX_IDLE_CONNECTIONS", "0")
	t.Setenv("DATABASE_CONNECTION_MAX_IDLE_TIME", "30s")
	c, err := Load()
	if err != nil || c.DatabaseMaxOpen != 4 || c.DatabaseMaxIdle != 0 || c.DatabaseMaxIdleTime != 30*time.Second {
		t.Fatal("serverless pool config rejected")
	}
	for _, value := range []string{"0", "65", "many"} {
		t.Setenv("DATABASE_MAX_OPEN_CONNECTIONS", value)
		if _, err := Load(); err == nil {
			t.Fatalf("invalid max open connections accepted: %q", value)
		}
	}
	t.Setenv("DATABASE_MAX_OPEN_CONNECTIONS", "4")
	for _, value := range []string{"-1", "5", "many"} {
		t.Setenv("DATABASE_MAX_IDLE_CONNECTIONS", value)
		if _, err := Load(); err == nil {
			t.Fatalf("invalid max idle connections accepted: %q", value)
		}
	}
	t.Setenv("DATABASE_MAX_IDLE_CONNECTIONS", "0")
	for _, value := range []string{"0", "500ms", "31m", "forever"} {
		t.Setenv("DATABASE_CONNECTION_MAX_IDLE_TIME", value)
		if _, err := Load(); err == nil {
			t.Fatalf("invalid idle lifetime accepted: %q", value)
		}
	}
	t.Setenv("DATABASE_MAX_IDLE_CONNECTIONS", "")
	t.Setenv("DATABASE_CONNECTION_MAX_IDLE_TIME", "")
	c, err = Load()
	if err != nil || c.DatabaseMaxIdle != 4 {
		t.Fatal("default idle count was not bounded by a smaller open pool")
	}
}
