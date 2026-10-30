// Package devices lets one account use NovaChat on more than one device.
// The phone that signed up with the number stays the main device; it can
// link up to MaxLinked more (a tablet, a second phone) by typing the code
// the new device shows. Every device keeps its own encryption keys, so
// messages are encrypted for each one separately.
package devices

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"math/big"
	"net"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/redis/go-redis/v9"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/chat"
	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

const (
	// MaxLinked is how many devices can be linked to one phone.
	MaxLinked = 4
	linkTTL   = 5 * time.Minute
	// A signed-in device has this long to pick up its token.
	pickupTTL = 2 * time.Minute
	// Codes a new device may ask for per network address, per hour.
	maxStartsPerHour = 20
	// Codes a phone may try per 10 minutes, so codes can't be guessed.
	maxApprovals = 10
	pending      = "pending"
)

type Handlers struct {
	Auth  *auth.Handlers
	Hub   *chat.Hub
	Redis *redis.Client
}

func (h *Handlers) Register(mux *http.ServeMux) {
	mux.Handle("GET /v1/devices", h.Auth.Require(http.HandlerFunc(h.list)))
	mux.Handle("DELETE /v1/devices/{id}", h.Auth.Require(http.HandlerFunc(h.remove)))
	mux.HandleFunc("POST /v1/link/start", h.start)
	mux.HandleFunc("GET /v1/link/{poll}", h.poll)
	mux.Handle("POST /v1/link/approve", h.Auth.Require(http.HandlerFunc(h.approve)))
}

func (h *Handlers) list(w http.ResponseWriter, r *http.Request) {
	d := auth.CurrentDevice(r.Context())
	ds, err := h.Auth.Store.Sessions(r.Context(), d.UserID, d.ID)
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, ds)
}

// remove signs a linked device out. The main phone can't be removed this
// way; it signs out with "Log out" on the phone itself.
func (h *Handlers) remove(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	d := auth.CurrentDevice(ctx)
	id := strings.ToLower(r.PathValue("id"))
	ds, err := h.Auth.Store.Sessions(ctx, d.UserID, d.ID)
	if err != nil {
		internalError(w, err)
		return
	}
	var target *auth.SessionInfo
	for i := range ds {
		if ds[i].ID == id {
			target = &ds[i]
		}
	}
	switch {
	case target == nil:
		httpx.Error(w, http.StatusNotFound, "not_found", "no such device")
		return
	case !target.Linked:
		httpx.Error(w, http.StatusForbidden, "main_device", "log out on the phone itself")
		return
	}
	if _, err := h.Auth.Store.DeleteDevice(ctx, d.UserID, id); err != nil {
		internalError(w, err)
		return
	}
	h.Hub.Kick(ctx, id)
	w.WriteHeader(http.StatusNoContent)
}

// link is what Redis holds for a code while the new device waits.
type link struct {
	Poll string `json:"poll"`
	Name string `json:"name"`
}

// start is called by the new device: it gets a code to show and a secret
// to poll with until the phone approves.
func (h *Handlers) start(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DeviceName string `json:"device_name"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	ctx := r.Context()
	if !h.allow(ctx, "link:ip:"+clientIP(r), maxStartsPerHour, time.Hour) {
		httpx.Error(w, http.StatusTooManyRequests, "rate_limited", "too many tries, wait a while")
		return
	}
	name := strings.TrimSpace(req.DeviceName)
	if name == "" {
		name = "Linked device"
	}
	if utf8.RuneCountInString(name) > 100 {
		name = string([]rune(name)[:100])
	}
	poll, err := randomToken()
	if err != nil {
		internalError(w, err)
		return
	}
	var code string
	for range 5 {
		n, err := rand.Int(rand.Reader, big.NewInt(100_000_000))
		if err != nil {
			internalError(w, err)
			return
		}
		code = pad8(n.Int64())
		b, _ := json.Marshal(link{Poll: poll, Name: name})
		ok, err := h.Redis.SetNX(ctx, "link:code:"+code, b, linkTTL).Result()
		if err != nil {
			internalError(w, err)
			return
		}
		if ok {
			break
		}
		code = ""
	}
	if code == "" {
		internalError(w, errors.New("no free link code"))
		return
	}
	if err := h.Redis.Set(ctx, "link:poll:"+poll, pending, linkTTL).Err(); err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"code": code[:4] + "-" + code[4:], "poll": poll, "expires_in": int(linkTTL.Seconds()),
	})
}

// poll answers 202 until the phone approves, then hands over the session
// once (200), or 404 when the code expired.
func (h *Handlers) poll(w http.ResponseWriter, r *http.Request) {
	key := "link:poll:" + r.PathValue("poll")
	v, err := h.Redis.Get(r.Context(), key).Result()
	switch {
	case errors.Is(err, redis.Nil):
		httpx.Error(w, http.StatusNotFound, "expired", "the code expired, start again")
		return
	case err != nil:
		internalError(w, err)
		return
	case v == pending:
		httpx.JSON(w, http.StatusAccepted, map[string]string{"status": pending})
		return
	}
	// GETDEL so only one request ever gets the token.
	v, err = h.Redis.GetDel(r.Context(), key).Result()
	if err != nil {
		httpx.Error(w, http.StatusNotFound, "expired", "the code expired, start again")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write([]byte(v))
}

// approve is called by the main phone with the code the new device shows.
func (h *Handlers) approve(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code string `json:"code"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	ctx := r.Context()
	d := auth.CurrentDevice(ctx)
	if d.Linked {
		httpx.Error(w, http.StatusForbidden, "linked_device", "link new devices from your phone")
		return
	}
	if !h.allow(ctx, "link:try:"+d.UserID, maxApprovals, 10*time.Minute) {
		httpx.Error(w, http.StatusTooManyRequests, "rate_limited", "too many tries, wait a few minutes")
		return
	}
	code := digits(req.Code)
	if len(code) != 8 {
		httpx.Error(w, http.StatusBadRequest, "bad_code", "the code has 8 digits")
		return
	}
	n, err := h.Auth.Store.CountLinked(ctx, d.UserID)
	if err != nil {
		internalError(w, err)
		return
	}
	if n >= MaxLinked {
		httpx.Error(w, http.StatusConflict, "too_many_devices", "you can link up to 4 devices; remove one first")
		return
	}
	raw, err := h.Redis.GetDel(ctx, "link:code:"+code).Result()
	if errors.Is(err, redis.Nil) {
		httpx.Error(w, http.StatusNotFound, "bad_code", "wrong or expired code")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	var l link
	if err := json.Unmarshal([]byte(raw), &l); err != nil {
		internalError(w, err)
		return
	}
	token, deviceID, err := h.Auth.Store.CreateLinkedSession(ctx, d.UserID, l.Name)
	if err != nil {
		internalError(w, err)
		return
	}
	user, err := h.Auth.Store.UserFor(ctx, d.UserID, d.UserID)
	if err != nil {
		internalError(w, err)
		return
	}
	b, _ := json.Marshal(map[string]any{"token": token, "user": user, "is_new": false})
	// Only if the new device is still waiting; otherwise undo the sign-in.
	ok, err := h.Redis.SetXX(ctx, "link:poll:"+l.Poll, b, pickupTTL).Result()
	if err != nil || !ok {
		h.Auth.Store.DeleteDevice(ctx, d.UserID, deviceID)
		if err != nil {
			internalError(w, err)
			return
		}
		httpx.Error(w, http.StatusNotFound, "bad_code", "wrong or expired code")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]string{"id": deviceID, "name": l.Name})
}

// allow counts one try against a limit per window.
func (h *Handlers) allow(ctx context.Context, key string, limit int64, window time.Duration) bool {
	n, err := h.Redis.Incr(ctx, key).Result()
	if err != nil {
		slog.Error("rate limit failed", "err", err)
		return false
	}
	if n == 1 {
		h.Redis.Expire(ctx, key, window)
	}
	return n <= limit
}

func clientIP(r *http.Request) string {
	// Behind Render's proxy the first forwarded address is the device.
	if f := r.Header.Get("X-Forwarded-For"); f != "" {
		return strings.TrimSpace(strings.Split(f, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func digits(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}

func pad8(n int64) string {
	s := "00000000" + big.NewInt(n).String()
	return s[len(s)-8:]
}

func randomToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func internalError(w http.ResponseWriter, err error) {
	slog.Error("request failed", "err", err)
	httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
}
