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
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	_, err := s.DB.Exec(ctx, `INSERT INTO sessions (token_hash, user_id, device_name) VALUES ($1, $2, $3)`,
		hashToken(token), userID, deviceName)
	return token, err
}

// UserIDForToken resolves a bearer token to its user and bumps last_seen_at.
func (s *Store) UserIDForToken(ctx context.Context, token string) (string, error) {
	var id string
	err := s.DB.QueryRow(ctx, `
		UPDATE sessions SET last_seen_at = now() WHERE token_hash = $1
		RETURNING user_id::text`, hashToken(token)).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNotFound
	}
	return id, err
}

func (s *Store) DeleteSession(ctx context.Context, token string) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE token_hash = $1`, hashToken(token))
	return err
}

// DeleteOtherSessions signs userID out everywhere except the device holding
// keepToken. NovaChat is one phone per account (like a SIM): a phone that
// publishes new encryption keys takes the account over.
func (s *Store) DeleteOtherSessions(ctx context.Context, userID, keepToken string) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM sessions WHERE user_id = $1 AND token_hash <> $2`, userID, hashToken(keepToken))
	return err
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
