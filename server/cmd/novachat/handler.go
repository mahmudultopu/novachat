package main

import (
	"context"
	"net/http"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/chat"
	"github.com/mahmudultopu/novachat/server/internal/config"
	"github.com/mahmudultopu/novachat/server/internal/groups"
	"github.com/mahmudultopu/novachat/server/internal/keys"
	"github.com/mahmudultopu/novachat/server/internal/media"
)

// apkURL is where invite pages send people who don't have the app yet.
const apkURL = "https://github.com/mahmudultopu/novachat/releases/download/latest/novachat.apk"

// NewHandler wires every route. It returns a function that stops the hub.
func NewHandler(ctx context.Context, cfg config.Config, pool *pgxpool.Pool, rdb *redis.Client) (http.Handler, func()) {
	otp := &auth.OTP{Redis: rdb}
	if cfg.IsDev() {
		otp.DevCode = cfg.DevOTP
	}
	authH := &auth.Handlers{Store: &auth.Store{DB: pool}, OTP: otp}
	hub := chat.NewHub(ctx, rdb)
	keyStore := &keys.Store{DB: pool}
	keysH := &keys.Handlers{Store: keyStore, Auth: authH}
	chatH := &chat.Handlers{Store: &chat.Store{DB: pool}, Hub: hub, Auth: authH, Keys: keyStore}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("ok"))
	})
	authH.Register(mux)
	mediaStore := &media.Store{DB: pool}
	go mediaStore.RunCleanup(ctx)
	(&media.Handlers{Store: mediaStore, Auth: authH}).Register(mux)
	keysH.Register(mux)
	chatH.Register(mux)
	(&groups.Handlers{DB: pool, Hub: hub, Auth: authH, APKURL: apkURL}).Register(mux)
	return mux, func() { hub.Close() }
}
