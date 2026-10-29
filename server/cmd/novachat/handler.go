package main

import (
	"context"
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/chat"
	"github.com/mahmudultopu/novachat/server/internal/config"
)

// NewHandler wires every route. It returns a function that stops the hub.
func NewHandler(ctx context.Context, cfg config.Config, pool *pgxpool.Pool, rdb *redis.Client) (http.Handler, func()) {
	otp := &auth.OTP{Redis: rdb}
	if cfg.IsDev() {
		otp.DevCode = cfg.DevOTP
	}
	authH := &auth.Handlers{Store: &auth.Store{DB: pool}, OTP: otp}
	hub := chat.NewHub(ctx, rdb)
	chatH := &chat.Handlers{Store: &chat.Store{DB: pool}, Hub: hub, Auth: authH}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	authH.Register(mux)
	chatH.Register(mux)
	return mux, func() { hub.Close() }
}
