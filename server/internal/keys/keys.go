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

// Bundle is what a phone needs to start a session with one device.
type Bundle struct {
	UserID        string         `json:"user_id"`
	DeviceID      string         `json:"device_id"`
	IdentityKey   Key            `json:"identity_key"`
	SigningKey    Key            `json:"signing_key"`
	SignedPreKey  SignedPreKey   `json:"signed_prekey"`
	OneTimePreKey *OneTimePreKey `json:"one_time_prekey,omitempty"`
}

// Device is one of a user's devices that can receive messages.
type Device struct {
	ID          string `json:"id"`
	IdentityKey Key    `json:"identity_key"`
}

type Store struct {
	DB *pgxpool.Pool
}

// Devices lists the user's devices that have published keys, oldest first.
func (s *Store) Devices(ctx context.Context, userID string) ([]Device, error) {
	rows, err := s.DB.Query(ctx, `
		SELECT k.device_id::text, k.identity_key FROM device_keys k JOIN sessions s ON s.id = k.device_id
		WHERE k.user_id = $1 ORDER BY s.created_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Device{}
	for rows.Next() {
		var d Device
		var k []byte
		if err := rows.Scan(&d.ID, &k); err != nil {
			return nil, err
		}
		d.IdentityKey = k
		out = append(out, d)
	}
	return out, rows.Err()
}

// Publish stores a device's keys. A new identity key (a reinstall) drops the
// device's unused one-time prekeys. It returns how many are left.
func (s *Store) Publish(ctx context.Context, userID, deviceID string, identity, signing Key, spk SignedPreKey, otks []OneTimePreKey) (left int, err error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)

	var changed bool
	err = tx.QueryRow(ctx, `
		INSERT INTO device_keys (device_id, user_id, identity_key, signing_key, spk_id, spk, spk_signature)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (device_id) DO UPDATE SET identity_key = EXCLUDED.identity_key,
			signing_key = EXCLUDED.signing_key, spk_id = EXCLUDED.spk_id, spk = EXCLUDED.spk,
			spk_signature = EXCLUDED.spk_signature, updated_at = now()
		RETURNING (SELECT identity_key FROM device_keys WHERE device_id = $1) IS DISTINCT FROM $3`,
		deviceID, userID, []byte(identity), []byte(signing), spk.ID, []byte(spk.PublicKey), []byte(spk.Signature)).Scan(&changed)
	if err != nil {
		return 0, err
	}
	if changed {
		if _, err := tx.Exec(ctx, `DELETE FROM device_prekeys WHERE device_id = $1`, deviceID); err != nil {
			return 0, err
		}
	}
	for _, k := range otks {
		if _, err := tx.Exec(ctx, `
			INSERT INTO device_prekeys (device_id, key_id, public_key) VALUES ($1, $2, $3)
			ON CONFLICT DO NOTHING`, deviceID, k.ID, []byte(k.PublicKey)); err != nil {
			return 0, err
		}
	}
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM device_prekeys WHERE device_id = $1`, deviceID).Scan(&left); err != nil {
		return 0, err
	}
	return left, tx.Commit(ctx)
}

// Claim returns one device's bundle and removes the one-time prekey it hands
// out, so no two people get the same one. When none are left the bundle has
// no one-time prekey, which X3DH allows.
func (s *Store) Claim(ctx context.Context, userID, deviceID string) (Bundle, error) {
	b := Bundle{UserID: userID, DeviceID: deviceID}
	var ik, sk, spk, sig []byte
	err := s.DB.QueryRow(ctx, `
		SELECT identity_key, signing_key, spk_id, spk, spk_signature FROM device_keys
		WHERE user_id = $1 AND device_id = $2`, userID, deviceID).
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
		DELETE FROM device_prekeys WHERE (device_id, key_id) = (
			SELECT device_id, key_id FROM device_prekeys WHERE device_id = $1
			ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED)
		RETURNING key_id, public_key`, deviceID).Scan(&otk.ID, &otkKey)
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
	mux.Handle("GET /v1/keys/{userID}", h.Auth.Require(http.HandlerFunc(h.devices)))
	mux.Handle("GET /v1/keys/{userID}/{deviceID}", h.Auth.Require(http.HandlerFunc(h.claim)))
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
	d := auth.CurrentDevice(r.Context())
	left, err := h.Store.Publish(r.Context(), d.UserID, d.ID, req.IdentityKey, req.SigningKey, req.SignedPreKey, req.OneTimePreKeys)
	if err != nil {
		internalError(w, err)
		return
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

// devices lists who to encrypt for: each of the user's devices with its identity key.
func (h *Handlers) devices(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("userID")
	if !auth.IsUUID(id) {
		httpx.Error(w, http.StatusBadRequest, "bad_user", "not a user id")
		return
	}
	ds, err := h.Store.Devices(r.Context(), id)
	if err != nil {
		internalError(w, err)
		return
	}
	if len(ds) == 0 {
		httpx.Error(w, http.StatusNotFound, "no_keys", "this person needs to update NovaChat before you can message them")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"devices": ds})
}

func (h *Handlers) claim(w http.ResponseWriter, r *http.Request) {
	id, device := r.PathValue("userID"), r.PathValue("deviceID")
	if !auth.IsUUID(id) || !auth.IsUUID(device) {
		httpx.Error(w, http.StatusBadRequest, "bad_user", "not a user or device id")
		return
	}
	b, err := h.Store.Claim(r.Context(), id, device)
	if errors.Is(err, ErrNoKeys) {
		httpx.Error(w, http.StatusNotFound, "no_keys", "that device is no longer signed in")
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
