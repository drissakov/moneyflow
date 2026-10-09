package config

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	DatabaseURL    string
	Port           string
	SessionTTL     time.Duration
	Production     bool
	TrustedProxies []string
}

func Load() (Config, error) {
	c := Config{DatabaseURL: os.Getenv("DATABASE_URL"), Port: os.Getenv("PORT"), SessionTTL: 30 * 24 * time.Hour}
	if c.DatabaseURL == "" {
		return c, fmt.Errorf("DATABASE_URL is required")
	}
	if c.Port == "" {
		c.Port = "8080"
	}
	port, err := strconv.Atoi(c.Port)
	if err != nil || port < 1 || port > 65535 {
		return c, fmt.Errorf("PORT must be between 1 and 65535")
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
