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
	// Calls: STUN servers, and optionally a TURN server (coturn with a
	// shared secret, or Cloudflare's TURN service) for phones that can't
	// reach each other directly.
	STUNURLs        []string
	TURNURLs        []string
	TURNSecret      string
	CloudflareKeyID string
	CloudflareToken string
}

func (c Config) IsDev() bool { return c.Env == "dev" }

func Load() (Config, error) {
	c := Config{
		Env:         get("NOVACHAT_ENV", "dev"),
		HTTPAddr:    get("NOVACHAT_HTTP_ADDR", ":"+get("PORT", "8080")),
		DatabaseURL: get("NOVACHAT_DATABASE_URL", "postgres://novachat:novachat@localhost:5432/novachat?sslmode=disable"),
		RedisURL:    get("NOVACHAT_REDIS_URL", "redis://localhost:6379/0"),
		DevOTP:      get("NOVACHAT_DEV_OTP", "123456"),

		STUNURLs:        list(get("NOVACHAT_STUN_URLS", "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302")),
		TURNURLs:        list(get("NOVACHAT_TURN_URLS", "")),
		TURNSecret:      get("NOVACHAT_TURN_SECRET", ""),
		CloudflareKeyID: get("NOVACHAT_CLOUDFLARE_TURN_KEY_ID", ""),
		CloudflareToken: get("NOVACHAT_CLOUDFLARE_TURN_TOKEN", ""),
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

// list splits a comma-separated setting, dropping empty entries.
func list(v string) []string {
	var out []string
	for _, s := range strings.Split(v, ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}
