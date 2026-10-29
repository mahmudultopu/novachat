package auth

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

// Handlers serves sign-up, sign-in and profile endpoints.
type Handlers struct {
	Store *Store
	OTP   *OTP
}

func (h *Handlers) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/auth/request-code", h.requestCode)
	mux.HandleFunc("POST /v1/auth/verify", h.verify)
	mux.Handle("POST /v1/auth/logout", h.Require(http.HandlerFunc(h.logout)))
	mux.Handle("GET /v1/me", h.Require(http.HandlerFunc(h.me)))
	mux.Handle("PATCH /v1/me", h.Require(http.HandlerFunc(h.updateMe)))
	mux.Handle("GET /v1/users/by-phone", h.Require(http.HandlerFunc(h.userByPhone)))
	mux.Handle("GET /v1/users/{id}", h.Require(http.HandlerFunc(h.userByID)))
}

func (h *Handlers) requestCode(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Phone string `json:"phone"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	phone, err := NormalizePhone(req.Phone)
	if err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid_phone", err.Error())
		return
	}
	devCode, err := h.OTP.Request(r.Context(), phone)
	switch {
	case errors.Is(err, ErrTooManyRequests):
		httpx.Error(w, http.StatusTooManyRequests, "rate_limited", err.Error())
		return
	case errors.Is(err, ErrNoSMSProvider):
		httpx.Error(w, http.StatusNotImplemented, "no_sms_provider", err.Error())
		return
	case err != nil:
		internalError(w, err)
		return
	}
	resp := map[string]any{"phone": phone, "expires_in": int(otpTTL.Seconds())}
	if devCode != "" {
		resp["dev_code"] = devCode
	}
	httpx.JSON(w, http.StatusOK, resp)
}

func (h *Handlers) verify(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Phone      string `json:"phone"`
		Code       string `json:"code"`
		DeviceName string `json:"device_name"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	phone, err := NormalizePhone(req.Phone)
	if err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid_phone", err.Error())
		return
	}
	err = h.OTP.Verify(r.Context(), phone, strings.TrimSpace(req.Code))
	switch {
	case errors.Is(err, ErrBadCode):
		httpx.Error(w, http.StatusUnauthorized, "bad_code", err.Error())
		return
	case errors.Is(err, ErrTooManyAttempts):
		httpx.Error(w, http.StatusTooManyRequests, "too_many_attempts", err.Error())
		return
	case err != nil:
		internalError(w, err)
		return
	}
	user, created, err := h.Store.UpsertUser(r.Context(), phone)
	if err != nil {
		internalError(w, err)
		return
	}
	token, err := h.Store.CreateSession(r.Context(), user.ID, truncate(req.DeviceName, 100))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"token": token, "user": user, "is_new": created})
}

func (h *Handlers) logout(w http.ResponseWriter, r *http.Request) {
	if err := h.Store.DeleteSession(r.Context(), bearerToken(r)); err != nil {
		internalError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) me(w http.ResponseWriter, r *http.Request) {
	h.writeUser(w, r, UserID(r.Context()))
}

func (h *Handlers) updateMe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name  *string `json:"name"`
		About *string `json:"about"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	if req.Name != nil {
		n := strings.TrimSpace(*req.Name)
		if n == "" || utf8.RuneCountInString(n) > 25 {
			httpx.Error(w, http.StatusBadRequest, "invalid_name", "name must be 1 to 25 characters")
			return
		}
		req.Name = &n
	}
	if req.About != nil && utf8.RuneCountInString(*req.About) > 140 {
		httpx.Error(w, http.StatusBadRequest, "invalid_about", "about must be at most 140 characters")
		return
	}
	u, err := h.Store.UpdateProfile(r.Context(), UserID(r.Context()), req.Name, req.About)
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, u)
}

func (h *Handlers) userByPhone(w http.ResponseWriter, r *http.Request) {
	phone, err := NormalizePhone(r.URL.Query().Get("phone"))
	if err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid_phone", err.Error())
		return
	}
	u, err := h.Store.UserByPhone(r.Context(), phone)
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusNotFound, "not_on_novachat", "this number is not on NovaChat yet")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, u)
}

func (h *Handlers) userByID(w http.ResponseWriter, r *http.Request) {
	h.writeUser(w, r, r.PathValue("id"))
}

func (h *Handlers) writeUser(w http.ResponseWriter, r *http.Request, id string) {
	u, err := h.Store.UserByID(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusNotFound, "not_found", "user not found")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, u)
}

type ctxKey struct{}

// Require rejects requests without a valid bearer token and puts the
// caller's user ID in the request context. The token may also be passed as
// ?token= for WebSocket clients that cannot set headers.
func (h *Handlers) Require(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token := bearerToken(r)
		if token == "" {
			httpx.Error(w, http.StatusUnauthorized, "unauthorized", "missing token")
			return
		}
		id, err := h.Store.UserIDForToken(r.Context(), token)
		if errors.Is(err, ErrNotFound) {
			httpx.Error(w, http.StatusUnauthorized, "unauthorized", "invalid or expired token")
			return
		}
		if err != nil {
			internalError(w, err)
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, id)))
	})
}

// UserID returns the signed-in user set by Require.
func UserID(ctx context.Context) string {
	id, _ := ctx.Value(ctxKey{}).(string)
	return id
}

func bearerToken(r *http.Request) string {
	if h := r.Header.Get("Authorization"); strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	return r.URL.Query().Get("token")
}

func internalError(w http.ResponseWriter, err error) {
	slog.Error("request failed", "err", err)
	httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
}

func truncate(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}
