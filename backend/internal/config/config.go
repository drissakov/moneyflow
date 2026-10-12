package config

import (
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/drissakov/moneyflow/backend/internal/fx"
)

type Config struct {
	DatabaseURL         string
	DatabaseMaxOpen     int
	DatabaseMaxIdle     int
	DatabaseMaxIdleTime time.Duration
	Port                string
	MetricsPort         string
	SessionTTL          time.Duration
	Production          bool
	TrustedProxies      []string
	FrankfurterURL      string
	FXCacheTTL          time.Duration
	FXMaxStale          time.Duration
}

func Load() (Config, error) {
	c := Config{DatabaseURL: os.Getenv("DATABASE_URL"), Port: os.Getenv("PORT"), MetricsPort: os.Getenv("METRICS_PORT"), SessionTTL: 30 * 24 * time.Hour}
	if path := os.Getenv("DATABASE_URL_FILE"); path != "" {
		if c.DatabaseURL != "" {
			return c, fmt.Errorf("DATABASE_URL and DATABASE_URL_FILE are mutually exclusive")
		}
		file, err := os.Open(path)
		if err != nil {
			return c, fmt.Errorf("could not read DATABASE_URL_FILE")
		}
		value, readErr := io.ReadAll(io.LimitReader(file, 16*1024+1))
		file.Close()
		if readErr != nil || len(value) > 16*1024 {
			return c, fmt.Errorf("DATABASE_URL_FILE must be readable and at most 16384 bytes")
		}
		c.DatabaseURL = strings.TrimSpace(string(value))
	}
	c.FrankfurterURL = os.Getenv("FRANKFURTER_URL")
	if c.FrankfurterURL == "" {
		c.FrankfurterURL = "https://api.frankfurter.dev/v2"
	}
	if err := fx.ValidateURL(c.FrankfurterURL); err != nil {
		return c, err
	}
	c.FXCacheTTL = 6 * time.Hour
	c.FXMaxStale = 7 * 24 * time.Hour
	if raw := os.Getenv("FX_CACHE_TTL"); raw != "" {
		value, err := time.ParseDuration(raw)
		if err != nil || value < time.Minute || value > 24*time.Hour {
			return c, fmt.Errorf("FX_CACHE_TTL must be between 1m and 24h")
		}
		c.FXCacheTTL = value
	}
	if raw := os.Getenv("FX_MAX_STALE"); raw != "" {
		value, err := time.ParseDuration(raw)
		if err != nil || value < time.Hour || value > 30*24*time.Hour {
			return c, fmt.Errorf("FX_MAX_STALE must be between 1h and 720h")
		}
		c.FXMaxStale = value
	}
	if c.FXMaxStale < c.FXCacheTTL {
		return c, fmt.Errorf("FX_MAX_STALE must be at least FX_CACHE_TTL")
	}
	if c.DatabaseURL == "" {
		return c, fmt.Errorf("DATABASE_URL or DATABASE_URL_FILE is required")
	}
	c.DatabaseMaxOpen = 20
	c.DatabaseMaxIdle = 5
	c.DatabaseMaxIdleTime = 5 * time.Minute
	if raw := os.Getenv("DATABASE_MAX_OPEN_CONNECTIONS"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 64 {
			return c, fmt.Errorf("DATABASE_MAX_OPEN_CONNECTIONS must be between 1 and 64")
		}
		c.DatabaseMaxOpen = value
	}
	if raw := os.Getenv("DATABASE_MAX_IDLE_CONNECTIONS"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 0 || value > c.DatabaseMaxOpen {
			return c, fmt.Errorf("DATABASE_MAX_IDLE_CONNECTIONS must be between 0 and DATABASE_MAX_OPEN_CONNECTIONS")
		}
		c.DatabaseMaxIdle = value
	}
	if c.DatabaseMaxIdle > c.DatabaseMaxOpen {
		c.DatabaseMaxIdle = c.DatabaseMaxOpen
	}
	if raw := os.Getenv("DATABASE_CONNECTION_MAX_IDLE_TIME"); raw != "" {
		value, err := time.ParseDuration(raw)
		if err != nil || value < time.Second || value > 30*time.Minute {
			return c, fmt.Errorf("DATABASE_CONNECTION_MAX_IDLE_TIME must be between 1s and 30m")
		}
		c.DatabaseMaxIdleTime = value
	}
	if c.Port == "" {
		c.Port = "8080"
	}
	port, err := strconv.Atoi(c.Port)
	if err != nil || port < 1 || port > 65535 {
		return c, fmt.Errorf("PORT must be between 1 and 65535")
	}
	if c.MetricsPort != "" {
		metricsPort, err := strconv.Atoi(c.MetricsPort)
		if err != nil || metricsPort < 1 || metricsPort > 65535 {
			return c, fmt.Errorf("METRICS_PORT must be between 1 and 65535, or empty to disable")
		}
		if metricsPort == port {
			return c, fmt.Errorf("METRICS_PORT must differ from PORT")
		}
	}
	if value := os.Getenv("SESSION_TTL"); value != "" {
		c.SessionTTL, err = time.ParseDuration(value)
		if err != nil || c.SessionTTL < time.Minute || c.SessionTTL > 90*24*time.Hour {
			return c, fmt.Errorf("SESSION_TTL must be a duration between 1m and 2160h")
		}
	}
	switch os.Getenv("APP_ENV") {
	case "", "development", "test":
	case "production":
		c.Production = true
	default:
		return c, fmt.Errorf("APP_ENV must be development, test, or production")
	}
	if value := os.Getenv("TRUSTED_PROXIES"); value != "" {
		for _, raw := range strings.Split(value, ",") {
			proxy := strings.TrimSpace(raw)
			if net.ParseIP(proxy) == nil {
				if _, _, err := net.ParseCIDR(proxy); err != nil {
					return c, fmt.Errorf("TRUSTED_PROXIES must contain comma-separated IP addresses or CIDRs")
				}
			}
			c.TrustedProxies = append(c.TrustedProxies, proxy)
		}
	}
	return c, nil
}
