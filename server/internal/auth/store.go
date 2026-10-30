package auth

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type User struct {
	ID        string    `json:"id"`
	Phone     string    `json:"phone"`
	Name      string    `json:"name"`
	About     string    `json:"about"`
	CreatedAt time.Time `json:"created_at"`
	// AvatarID names the profile photo at /v1/avatars/{id}; empty for none.
	AvatarID   *string    `json:"avatar_id,omitempty"`
	LastSeenAt *time.Time `json:"last_seen_at,omitempty"`
}

var ErrNotFound = errors.New("not found")

// Store is the Postgres access for users and sessions.
type Store struct {
	DB *pgxpool.Pool
}

const userCols = `id::text, phone, name, about, created_at, avatar_id::text, last_seen_at`

func scanUser(row pgx.Row) (User, error) {
	var u User
	err := row.Scan(&u.ID, &u.Phone, &u.Name, &u.About, &u.CreatedAt, &u.AvatarID, &u.LastSeenAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return u, ErrNotFound
	}
	return u, err
}

// UpsertUser returns the user with this phone number, creating it if needed.
func (s *Store) UpsertUser(ctx context.Context, phone string) (u User, created bool, err error) {
	err = s.DB.QueryRow(ctx, `
		INSERT INTO users (phone) VALUES ($1)
		ON CONFLICT (phone) DO UPDATE SET phone = EXCLUDED.phone
		RETURNING `+userCols+`, (xmax = 0)`, phone).
		Scan(&u.ID, &u.Phone, &u.Name, &u.About, &u.CreatedAt, &u.AvatarID, &u.LastSeenAt, &created)
	return u, created, err
}

func (s *Store) UserByID(ctx context.Context, id string) (User, error) {
	if !IsUUID(id) {
		return User{}, ErrNotFound
	}
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+userCols+` FROM users WHERE id = $1`, id))
}

func (s *Store) UserByPhone(ctx context.Context, phone string) (User, error) {
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+userCols+` FROM users WHERE phone = $1`, phone))
}

func (s *Store) UpdateProfile(ctx context.Context, id string, name, about *string) (User, error) {
	return scanUser(s.DB.QueryRow(ctx, `
		UPDATE users SET name = COALESCE($2, name), about = COALESCE($3, about)
		WHERE id = $1 RETURNING `+userCols, id, name, about))
}

// SetAvatar stores a new profile photo and makes it current; the old one is deleted.
func (s *Store) SetAvatar(ctx context.Context, userID string, jpeg []byte) (User, error) {
	tx, err := s.DB.Begin(ctx)
	if err != nil {
		return User{}, err
	}
	defer tx.Rollback(ctx)
	var old *string
	if err := tx.QueryRow(ctx, `SELECT avatar_id::text FROM users WHERE id = $1`, userID).Scan(&old); err != nil {
		return User{}, err
	}
	var id string
	if err := tx.QueryRow(ctx, `INSERT INTO avatars (user_id, data) VALUES ($1, $2) RETURNING id::text`, userID, jpeg).Scan(&id); err != nil {
		return User{}, err
	}
	u, err := scanUser(tx.QueryRow(ctx, `UPDATE users SET avatar_id = $2 WHERE id = $1 RETURNING `+userCols, userID, id))
	if err != nil {
		return u, err
	}
	if old != nil {
		if _, err := tx.Exec(ctx, `DELETE FROM avatars WHERE id = $1`, *old); err != nil {
			return u, err
		}
	}
	return u, tx.Commit(ctx)
}

// RemoveAvatar deletes the user's profile photo.
func (s *Store) RemoveAvatar(ctx context.Context, userID string) (User, error) {
	// Only the profile photo: the same table also holds group photos this user set.
	if _, err := s.DB.Exec(ctx, `DELETE FROM avatars WHERE id = (SELECT avatar_id FROM users WHERE id = $1)`, userID); err != nil {
		return User{}, err
	}
	return s.UserByID(ctx, userID)
}

func (s *Store) Avatar(ctx context.Context, id string) ([]byte, error) {
	var data []byte
	err := s.DB.QueryRow(ctx, `SELECT data FROM avatars WHERE id = $1`, id).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return data, err
}

// TouchLastSeen records that the user just went offline.
func (s *Store) TouchLastSeen(ctx context.Context, userID string) error {
	_, err := s.DB.Exec(ctx, `UPDATE users SET last_seen_at = now() WHERE id = $1`, userID)
	return err
}

// CreateSession issues a new random bearer token for a device. Only the
// token's hash is stored.
func (s *Store) CreateSession(ctx context.Context, userID, deviceName string) (string, error) {
	token, err := newToken()
	if err != nil {
		return "", err
	}
	_, err = s.DB.Exec(ctx, `INSERT INTO sessions (token_hash, user_id, device_name) VALUES ($1, $2, $3)`,
		hashToken(token), userID, deviceName)
	return token, err
}

// CreateLinkedSession signs in a device linked from the user's phone. It
// gets messages from now on; older ones were never encrypted for it.
func (s *Store) CreateLinkedSession(ctx context.Context, userID, deviceName string) (token, deviceID string, err error) {
	token, err = newToken()
	if err != nil {
		return "", "", err
	}
	err = s.DB.QueryRow(ctx, `
		INSERT INTO sessions (token_hash, user_id, device_name, linked, first_message_id)
		VALUES ($1, $2, $3, true, (SELECT COALESCE(max(id), 0) FROM messages))
		RETURNING id::text`, hashToken(token), userID, deviceName).Scan(&deviceID)
	return token, deviceID, err
}

func newToken() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

// Device is who is calling: the user and which of their devices.
type Device struct {
	UserID string
	ID     string
	Linked bool
}

// DeviceForToken resolves a bearer token to its device and bumps last_seen_at.
func (s *Store) DeviceForToken(ctx context.Context, token string) (Device, error) {
	var d Device
	err := s.DB.QueryRow(ctx, `
		UPDATE sessions SET last_seen_at = now() WHERE token_hash = $1
		RETURNING user_id::text, id::text, linked`, hashToken(token)).Scan(&d.UserID, &d.ID, &d.Linked)
	if errors.Is(err, pgx.ErrNoRows) {
		return d, ErrNotFound
	}
	return d, err
}

func (s *Store) DeleteSession(ctx context.Context, token string) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE token_hash = $1`, hashToken(token))
	return err
}

// DeleteOtherSessions signs userID out everywhere except the device holding
// keepToken. NovaChat is one phone per account (like a SIM): signing in on a
// new phone takes the account over, and the old phone's linked devices go too.
func (s *Store) DeleteOtherSessions(ctx context.Context, userID, keepToken string) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE user_id = $1 AND token_hash <> $2`, userID, hashToken(keepToken))
	return err
}

// SessionInfo is one signed-in device, as listed under Linked devices.
type SessionInfo struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	Linked     bool      `json:"linked"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
	Current    bool      `json:"current"`
}

// Sessions lists the user's devices: the phone first, then linked ones, oldest first.
func (s *Store) Sessions(ctx context.Context, userID, currentID string) ([]SessionInfo, error) {
	rows, err := s.DB.Query(ctx, `
		SELECT id::text, device_name, linked, created_at, last_seen_at FROM sessions
		WHERE user_id = $1 ORDER BY linked, created_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []SessionInfo{}
	for rows.Next() {
		var d SessionInfo
		if err := rows.Scan(&d.ID, &d.Name, &d.Linked, &d.CreatedAt, &d.LastSeenAt); err != nil {
			return nil, err
		}
		d.Current = d.ID == currentID
		out = append(out, d)
	}
	return out, rows.Err()
}

// CountLinked returns how many linked devices the user has.
func (s *Store) CountLinked(ctx context.Context, userID string) (int, error) {
	var n int
	err := s.DB.QueryRow(ctx, `SELECT count(*) FROM sessions WHERE user_id = $1 AND linked`, userID).Scan(&n)
	return n, err
}

// DeleteDevice signs one of the user's devices out. It reports false when
// the user has no such device.
func (s *Store) DeleteDevice(ctx context.Context, userID, deviceID string) (bool, error) {
	if !IsUUID(deviceID) {
		return false, nil
	}
	tag, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE user_id = $1 AND id = $2`, userID, deviceID)
	return tag.RowsAffected() > 0, err
}

func hashToken(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

// IsUUID reports whether s is a canonical lowercase or uppercase UUID string.
func IsUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i, r := range s {
		switch i {
		case 8, 13, 18, 23:
			if r != '-' {
				return false
			}
		default:
			if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f' || r >= 'A' && r <= 'F') {
				return false
			}
		}
	}
	return true
}
