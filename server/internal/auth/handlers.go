package auth

import (
	"context"
	"errors"
	"io"
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
	mux.Handle("PUT /v1/me/avatar", h.Require(http.HandlerFunc(h.setAvatar)))
	mux.Handle("DELETE /v1/me/avatar", h.Require(http.HandlerFunc(h.removeAvatar)))
	mux.Handle("GET /v1/avatars/{id}", h.Require(http.HandlerFunc(h.avatar)))
	mux.Handle("GET /v1/me/privacy", h.Require(http.HandlerFunc(h.privacy)))
	mux.Handle("PATCH /v1/me/privacy", h.Require(http.HandlerFunc(h.updatePrivacy)))
	mux.Handle("GET /v1/blocks", h.Require(http.HandlerFunc(h.blocked)))
	mux.Handle("PUT /v1/blocks/{id}", h.Require(http.HandlerFunc(h.block)))
	mux.Handle("DELETE /v1/blocks/{id}", h.Require(http.HandlerFunc(h.unblock)))
	mux.Handle("POST /v1/reports", h.Require(http.HandlerFunc(h.report)))
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
	u, err := h.Store.UserByPhoneFor(r.Context(), UserID(r.Context()), phone)
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
	// Others see only what the user's privacy settings share with them.
	u, err := h.Store.UserFor(r.Context(), UserID(r.Context()), id)
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

const maxAvatarBytes = 512 << 10

// setAvatar takes a JPEG profile photo as the raw request body. The app
// crops and shrinks it first, so 512 KB is plenty.
func (h *Handlers) setAvatar(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxAvatarBytes))
	var tooBig *http.MaxBytesError
	switch {
	case errors.As(err, &tooBig):
		httpx.Error(w, http.StatusRequestEntityTooLarge, "too_large", "profile photos can be at most 512 KB")
		return
	case err != nil:
		httpx.Error(w, http.StatusBadRequest, "bad_body", "could not read upload")
		return
	case len(data) < 3 || data[0] != 0xFF || data[1] != 0xD8 || data[2] != 0xFF:
		httpx.Error(w, http.StatusBadRequest, "not_jpeg", "profile photos must be JPEG")
		return
	}
	u, err := h.Store.SetAvatar(r.Context(), UserID(r.Context()), data)
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, u)
}

func (h *Handlers) removeAvatar(w http.ResponseWriter, r *http.Request) {
	u, err := h.Store.RemoveAvatar(r.Context(), UserID(r.Context()))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, u)
}

func (h *Handlers) avatar(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !IsUUID(id) {
		httpx.Error(w, http.StatusNotFound, "not_found", "no such photo")
		return
	}
	data, err := h.Store.Avatar(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusNotFound, "not_found", "no such photo")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	// A new photo gets a new id, so each one can be cached forever.
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Write(data)
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

// BearerToken returns the token the request was authenticated with.
func BearerToken(r *http.Request) string { return bearerToken(r) }

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
