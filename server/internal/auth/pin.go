package auth

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"golang.org/x/crypto/bcrypt"

	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

const (
	// PinChallengeTTL is how long someone has to enter their PIN after the SMS code.
	PinChallengeTTL = 10 * time.Minute
	// PinMaxFailures wrong PINs in a row lock sign-in for PinLockout.
	PinMaxFailures = 5
	PinLockout     = 12 * time.Hour
	// PinResetDelay is how long a "forgot PIN" request waits, so a thief with
	// the SIM can't simply reset it, while the real owner is told to wait.
	PinResetDelay = 7 * 24 * time.Hour
)

// pinState is what sign-in needs to know about a user's PIN.
type pinState struct {
	hash        *string
	failures    int
	lockedUntil *time.Time
	resetAt     *time.Time
}

func (s *Store) pinState(ctx context.Context, userID string) (pinState, error) {
	var p pinState
	err := s.DB.QueryRow(ctx, `SELECT pin_hash, pin_failures, pin_locked_until, pin_reset_at FROM users WHERE id = $1`, userID).
		Scan(&p.hash, &p.failures, &p.lockedUntil, &p.resetAt)
	return p, err
}

// HasPin reports whether the user turned on two-step verification.
func (s *Store) HasPin(ctx context.Context, userID string) (bool, error) {
	var on bool
	err := s.DB.QueryRow(ctx, `SELECT pin_hash IS NOT NULL FROM users WHERE id = $1`, userID).Scan(&on)
	return on, err
}

// SetPin sets (or with an empty hash, removes) the PIN and clears any lock or pending reset.
func (s *Store) SetPin(ctx context.Context, userID, hash string) error {
	var h *string
	if hash != "" {
		h = &hash
	}
	_, err := s.DB.Exec(ctx, `UPDATE users SET pin_hash = $2, pin_failures = 0, pin_locked_until = NULL, pin_reset_at = NULL WHERE id = $1`, userID, h)
	return err
}

// pinFailed counts a wrong PIN and locks sign-in after too many; it returns the attempts left.
func (s *Store) pinFailed(ctx context.Context, userID string) (left int, lockedUntil *time.Time, err error) {
	var failures int
	err = s.DB.QueryRow(ctx, `
		UPDATE users SET
			pin_failures = CASE WHEN pin_failures + 1 >= $2 THEN 0 ELSE pin_failures + 1 END,
			pin_locked_until = CASE WHEN pin_failures + 1 >= $2 THEN now() + $3::interval ELSE pin_locked_until END
		WHERE id = $1
		RETURNING pin_failures, pin_locked_until`, userID, PinMaxFailures, PinLockout.String()).Scan(&failures, &lockedUntil)
	if err != nil {
		return 0, nil, err
	}
	if lockedUntil != nil && lockedUntil.After(time.Now()) {
		return 0, lockedUntil, nil
	}
	return PinMaxFailures - failures, nil, nil
}

func (s *Store) setPinResetAt(ctx context.Context, userID string, at time.Time) error {
	_, err := s.DB.Exec(ctx, `UPDATE users SET pin_reset_at = $2 WHERE id = $1`, userID, at)
	return err
}

// ValidPin reports whether pin is exactly 6 digits.
func ValidPin(pin string) bool {
	if len(pin) != 6 {
		return false
	}
	for _, r := range pin {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

type pinChallenge struct {
	UserID     string
	DeviceName string
}

func (h *Handlers) newPinChallenge(ctx context.Context, userID, deviceName string) (string, error) {
	b := make([]byte, 24)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	token := hex.EncodeToString(b)
	err := h.OTP.Redis.Set(ctx, "pin:ch:"+token, userID+"\n"+deviceName, PinChallengeTTL).Err()
	return token, err
}

func (h *Handlers) pinChallenge(ctx context.Context, token string) (pinChallenge, error) {
	v, err := h.OTP.Redis.Get(ctx, "pin:ch:"+token).Result()
	if errors.Is(err, redis.Nil) {
		return pinChallenge{}, ErrNotFound
	}
	if err != nil {
		return pinChallenge{}, err
	}
	userID, device, _ := strings.Cut(v, "\n")
	return pinChallenge{userID, device}, nil
}

func (h *Handlers) pinStatus(w http.ResponseWriter, r *http.Request) {
	on, err := h.Store.HasPin(r.Context(), UserID(r.Context()))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]bool{"enabled": on})
}

func (h *Handlers) setPin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Pin string `json:"pin"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	if !ValidPin(req.Pin) {
		httpx.Error(w, http.StatusBadRequest, "bad_pin", "the PIN must be 6 digits")
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(req.Pin), bcrypt.DefaultCost)
	if err != nil {
		internalError(w, err)
		return
	}
	if err := h.Store.SetPin(r.Context(), UserID(r.Context()), string(hash)); err != nil {
		internalError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) removePin(w http.ResponseWriter, r *http.Request) {
	if err := h.Store.SetPin(r.Context(), UserID(r.Context()), ""); err != nil {
		internalError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// verifyPin finishes signing in after the SMS code, for accounts with a PIN.
// With "forgot", it starts (or, after PinResetDelay, completes) a reset.
func (h *Handlers) verifyPin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Challenge string `json:"challenge"`
		Pin       string `json:"pin"`
		Forgot    bool   `json:"forgot"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	ctx := r.Context()
	ch, err := h.pinChallenge(ctx, req.Challenge)
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusUnauthorized, "bad_challenge", "this sign-in has expired, request a new code")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	p, err := h.Store.pinState(ctx, ch.UserID)
	if err != nil {
		internalError(w, err)
		return
	}
	now := time.Now()
	switch {
	case p.hash == nil:
		// The PIN was removed meanwhile (or a reset already went through).
	case req.Forgot:
		if p.resetAt == nil {
			at := now.Add(PinResetDelay)
			if err := h.Store.setPinResetAt(ctx, ch.UserID, at); err != nil {
				internalError(w, err)
				return
			}
			p.resetAt = &at
		}
		if p.resetAt.After(now) {
			httpx.JSON(w, http.StatusForbidden, map[string]any{
				"error":    "pin_reset_pending",
				"message":  "you can sign in without your PIN after the date shown",
				"reset_at": p.resetAt,
			})
			return
		}
		if err := h.Store.SetPin(ctx, ch.UserID, ""); err != nil {
			internalError(w, err)
			return
		}
	case p.lockedUntil != nil && p.lockedUntil.After(now):
		httpx.JSON(w, http.StatusTooManyRequests, map[string]any{
			"error": "pin_locked", "message": "too many wrong PINs, try again later", "locked_until": p.lockedUntil,
		})
		return
	case bcrypt.CompareHashAndPassword([]byte(*p.hash), []byte(req.Pin)) != nil:
		left, until, err := h.Store.pinFailed(ctx, ch.UserID)
		if err != nil {
			internalError(w, err)
			return
		}
		if until != nil {
			httpx.JSON(w, http.StatusTooManyRequests, map[string]any{
				"error": "pin_locked", "message": "too many wrong PINs, try again later", "locked_until": until,
			})
			return
		}
		httpx.JSON(w, http.StatusUnauthorized, map[string]any{"error": "bad_pin", "message": "wrong PIN", "attempts_left": left})
		return
	default:
		// The owner is back: cancel any pending reset.
		if err := h.Store.SetPin(ctx, ch.UserID, *p.hash); err != nil {
			internalError(w, err)
			return
		}
	}
	h.OTP.Redis.Del(ctx, "pin:ch:"+req.Challenge)
	h.signIn(w, r, ch.UserID, ch.DeviceName, false)
}
