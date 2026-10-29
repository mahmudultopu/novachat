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
}

var ErrNotFound = errors.New("not found")

// Store is the Postgres access for users and sessions.
type Store struct {
	DB *pgxpool.Pool
}

const userCols = `id::text, phone, name, about, created_at`

func scanUser(row pgx.Row) (User, error) {
	var u User
	err := row.Scan(&u.ID, &u.Phone, &u.Name, &u.About, &u.CreatedAt)
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
		Scan(&u.ID, &u.Phone, &u.Name, &u.About, &u.CreatedAt, &created)
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
