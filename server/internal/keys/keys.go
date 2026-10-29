// Package keys is the end-to-end encryption key directory. Phones publish
// public identity keys and prekeys here; anyone starting a chat fetches a
// bundle to run X3DH. The server never sees a private key or a message key.
package keys

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"fmt"
	"log/slog"
	"net/http"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

const maxPreKeysPerUpload = 200

var ErrNoKeys = errors.New("user has not published keys")

// Key is 32 or 64 raw bytes, sent as standard base64.
type Key []byte

func (k Key) MarshalText() ([]byte, error) { return []byte(base64.StdEncoding.EncodeToString(k)), nil }

func (k *Key) UnmarshalText(b []byte) error {
	v, err := base64.StdEncoding.DecodeString(string(b))
	*k = v
	return err
}

type SignedPreKey struct {
	ID        int32 `json:"id"`
	PublicKey Key   `json:"public_key"`
	Signature Key   `json:"signature"`
}

type OneTimePreKey struct {
	ID        int32 `json:"id"`
	PublicKey Key   `json:"public_key"`
}

// Bundle is what a phone needs to start a session with someone.
type Bundle struct {
	UserID        string         `json:"user_id"`
	IdentityKey   Key            `json:"identity_key"`
	SigningKey    Key            `json:"signing_key"`
	SignedPreKey  SignedPreKey   `json:"signed_prekey"`
	OneTimePreKey *OneTimePreKey `json:"one_time_prekey,omitempty"`
}

type Store struct {
	DB *pgxpool.Pool
}

// IdentityKey returns the user's current X25519 identity key.
func (s *Store) IdentityKey(ctx context.Context, userID string) ([]byte, error) {
	var k []byte
	err := s.DB.QueryRow(ctx, `SELECT identity_key FROM identity_keys WHERE user_id = $1`, userID).Scan(&k)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNoKeys
	}
	return k, err
}

// Publish stores a user's keys. A new identity key (a reinstall or a new
// phone) replaces the old one and drops its unused one-time prekeys. It
// returns how many one-time prekeys are left and whether the identity changed.
func (s *Store) Publish(ctx context.Context, userID string, identity, signing Key, spk SignedPreKey, otks []OneTimePreKey) (left int, changed bool, err error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return 0, false, err
	}
	defer tx.Rollback(ctx)

	err = tx.QueryRow(ctx, `
		INSERT INTO identity_keys (user_id, identity_key, signing_key) VALUES ($1, $2, $3)
		ON CONFLICT (user_id) DO UPDATE SET identity_key = EXCLUDED.identity_key,
			signing_key = EXCLUDED.signing_key, updated_at = now()
		RETURNING (SELECT identity_key FROM identity_keys WHERE user_id = $1) IS DISTINCT FROM $2`,
		userID, []byte(identity), []byte(signing)).Scan(&changed)
	if err != nil {
		return 0, false, err
	}
	if changed {
		if _, err := tx.Exec(ctx, `DELETE FROM one_time_prekeys WHERE user_id = $1`, userID); err != nil {
			return 0, false, err
		}
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO signed_prekeys (user_id, key_id, public_key, signature) VALUES ($1, $2, $3, $4)
		ON CONFLICT (user_id) DO UPDATE SET key_id = EXCLUDED.key_id,
			public_key = EXCLUDED.public_key, signature = EXCLUDED.signature`,
		userID, spk.ID, []byte(spk.PublicKey), []byte(spk.Signature)); err != nil {
		return 0, false, err
	}
	for _, k := range otks {
		if _, err := tx.Exec(ctx, `
			INSERT INTO one_time_prekeys (user_id, key_id, public_key) VALUES ($1, $2, $3)
			ON CONFLICT DO NOTHING`, userID, k.ID, []byte(k.PublicKey)); err != nil {
			return 0, false, err
		}
	}
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM one_time_prekeys WHERE user_id = $1`, userID).Scan(&left); err != nil {
		return 0, false, err
	}
	return left, changed, tx.Commit(ctx)
}

// Claim returns a user's bundle and removes the one-time prekey it hands
// out, so no two people get the same one. When none are left the bundle has
// no one-time prekey, which X3DH allows.
func (s *Store) Claim(ctx context.Context, userID string) (Bundle, error) {
	b := Bundle{UserID: userID}
	var ik, sk, spk, sig []byte
	err := s.DB.QueryRow(ctx, `
		SELECT i.identity_key, i.signing_key, p.key_id, p.public_key, p.signature
		FROM identity_keys i JOIN signed_prekeys p USING (user_id)
		WHERE i.user_id = $1`, userID).
		Scan(&ik, &sk, &b.SignedPreKey.ID, &spk, &sig)
	if errors.Is(err, pgx.ErrNoRows) {
		return b, ErrNoKeys
	}
	if err != nil {
		return b, err
	}
	b.IdentityKey, b.SigningKey, b.SignedPreKey.PublicKey, b.SignedPreKey.Signature = ik, sk, spk, sig
	var otk OneTimePreKey
	var otkKey []byte
	err = s.DB.QueryRow(ctx, `
		DELETE FROM one_time_prekeys WHERE (user_id, key_id) = (
			SELECT user_id, key_id FROM one_time_prekeys WHERE user_id = $1
			ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
		RETURNING key_id, public_key`, userID).Scan(&otk.ID, &otkKey)
	switch {
	case err == nil:
		otk.PublicKey = otkKey
		b.OneTimePreKey = &otk
	case !errors.Is(err, pgx.ErrNoRows):
		return b, err
	}
	return b, nil
}

type Handlers struct {
	Store *Store
	Auth  *auth.Handlers
}

func (h *Handlers) Register(mux *http.ServeMux) {
	mux.Handle("PUT /v1/keys", h.Auth.Require(http.HandlerFunc(h.publish)))
	mux.Handle("GET /v1/keys/{userID}", h.Auth.Require(http.HandlerFunc(h.claim)))
}

func (h *Handlers) publish(w http.ResponseWriter, r *http.Request) {
	var req struct {
		IdentityKey    Key             `json:"identity_key"`
		SigningKey     Key             `json:"signing_key"`
		SignedPreKey   SignedPreKey    `json:"signed_prekey"`
		OneTimePreKeys []OneTimePreKey `json:"one_time_prekeys"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	if err := validate(req.IdentityKey, req.SigningKey, req.SignedPreKey, req.OneTimePreKeys); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid_keys", err.Error())
		return
	}
	userID := auth.UserID(r.Context())
	left, changed, err := h.Store.Publish(r.Context(), userID, req.IdentityKey, req.SigningKey, req.SignedPreKey, req.OneTimePreKeys)
	if err != nil {
		internalError(w, err)
		return
	}
	if changed {
		// A new phone took over the account: sign the old one out.
		if err := h.Auth.Store.DeleteOtherSessions(r.Context(), userID, auth.BearerToken(r)); err != nil {
			internalError(w, err)
			return
		}
	}
	httpx.JSON(w, http.StatusOK, map[string]int{"one_time_prekeys": left})
}

func validate(identity, signing Key, spk SignedPreKey, otks []OneTimePreKey) error {
	switch {
	case len(identity) != 32:
		return fmt.Errorf("identity_key must be 32 bytes")
	case len(signing) != ed25519.PublicKeySize:
		return fmt.Errorf("signing_key must be 32 bytes")
	case len(spk.PublicKey) != 32 || len(spk.Signature) != ed25519.SignatureSize:
		return fmt.Errorf("signed_prekey is malformed")
	case !ed25519.Verify(ed25519.PublicKey(signing), spk.PublicKey, spk.Signature):
		return fmt.Errorf("signed_prekey signature does not verify")
	case len(otks) > maxPreKeysPerUpload:
		return fmt.Errorf("at most %d one-time prekeys per upload", maxPreKeysPerUpload)
	}
	for _, k := range otks {
		if len(k.PublicKey) != 32 {
			return fmt.Errorf("one-time prekey %d must be 32 bytes", k.ID)
		}
	}
	return nil
}

func (h *Handlers) claim(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("userID")
	if !auth.IsUUID(id) {
		httpx.Error(w, http.StatusBadRequest, "bad_user", "not a user id")
		return
	}
	b, err := h.Store.Claim(r.Context(), id)
	if errors.Is(err, ErrNoKeys) {
		httpx.Error(w, http.StatusNotFound, "no_keys", "this person needs to update NovaChat before you can message them")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, b)
}

func internalError(w http.ResponseWriter, err error) {
	slog.Error("request failed", "err", err)
	httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
}
