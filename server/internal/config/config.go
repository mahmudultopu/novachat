// Package config reads server settings from environment variables.
package config

import (
	"fmt"
	"os"
	"strings"
)

type Config struct {
	// Env is "dev" or "prod". Dev mode enables the fixed OTP code and
	// returns it in API responses so the app can be tested without SMS.
	Env         string
	HTTPAddr    string
	DatabaseURL string
	RedisURL    string
	DevOTP      string
}

func (c Config) IsDev() bool { return c.Env == "dev" }

func Load() (Config, error) {
	c := Config{
		Env:         get("NOVACHAT_ENV", "dev"),
		HTTPAddr:    get("NOVACHAT_HTTP_ADDR", ":"+get("PORT", "8080")),
		DatabaseURL: get("NOVACHAT_DATABASE_URL", "postgres://novachat:novachat@localhost:5432/novachat?sslmode=disable"),
		RedisURL:    get("NOVACHAT_REDIS_URL", "redis://localhost:6379/0"),
		DevOTP:      get("NOVACHAT_DEV_OTP", "123456"),
	}
	if c.Env != "dev" && c.Env != "prod" {
		return c, fmt.Errorf("NOVACHAT_ENV must be dev or prod, got %q", c.Env)
	}
	return c, nil
}

func get(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}
