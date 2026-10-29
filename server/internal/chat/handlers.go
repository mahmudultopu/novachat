package chat

import (
	"log/slog"
	"math"
	"net/http"
	"strconv"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/httpx"
	"github.com/mahmudultopu/novachat/server/internal/keys"
)

type Handlers struct {
	Store *Store
	Hub   *Hub
	Auth  *auth.Handlers
	Keys  *keys.Store
}

func (h *Handlers) Register(mux *http.ServeMux) {
	mux.Handle("GET /v1/ws", h.Auth.Require(http.HandlerFunc(h.serveWS)))
	mux.Handle("GET /v1/conversations", h.Auth.Require(http.HandlerFunc(h.conversations)))
	mux.Handle("GET /v1/messages", h.Auth.Require(http.HandlerFunc(h.history)))
	mux.Handle("GET /v1/sync", h.Auth.Require(http.HandlerFunc(h.sync)))
}

func (h *Handlers) conversations(w http.ResponseWriter, r *http.Request) {
	convs, err := h.Store.Conversations(r.Context(), auth.UserID(r.Context()))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, convs)
}

func (h *Handlers) history(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	peer := q.Get("peer")
	if !auth.IsUUID(peer) {
		httpx.Error(w, http.StatusBadRequest, "bad_peer", "peer must be a user id")
		return
	}
	before := queryInt(q.Get("before"), math.MaxInt64)
	limit := clamp(queryInt(q.Get("limit"), 50), 1, 200)
	msgs, err := h.Store.History(r.Context(), auth.UserID(r.Context()), peer, before, int(limit))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, msgs)
}

func (h *Handlers) sync(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	after := queryInt(q.Get("after"), 0)
	limit := clamp(queryInt(q.Get("limit"), 500), 1, 1000)
	msgs, err := h.Store.Sync(r.Context(), auth.UserID(r.Context()), after, int(limit))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, msgs)
}

func queryInt(s string, fallback int64) int64 {
	if v, err := strconv.ParseInt(s, 10, 64); err == nil {
		return v
	}
	return fallback
}

func clamp(v, lo, hi int64) int64 { return max(lo, min(v, hi)) }

func internalError(w http.ResponseWriter, err error) {
	slog.Error("request failed", "err", err)
	httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
}
