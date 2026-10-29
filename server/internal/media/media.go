// Package media stores end-to-end encrypted attachments. The server never
// has the keys: it keeps opaque blobs for a limited time so the recipient's
// phone can download them.
package media

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

const (
	// MaxSize is the largest encrypted file accepted (about 16 MB of media).
	MaxSize = 16<<20 + 64
	// Retention is how long blobs are kept; phones download them long before.
	Retention = 30 * 24 * time.Hour
)

var ErrNotFound = errors.New("attachment not found")

type Store struct {
	DB *pgxpool.Pool
}

func (s *Store) Put(ctx context.Context, ownerID string, data []byte) (string, error) {
	var id string
	err := s.DB.QueryRow(ctx,
		`INSERT INTO attachments (owner_id, size, data) VALUES ($1, $2, $3) RETURNING id::text`,
		ownerID, len(data), data).Scan(&id)
	return id, err
}

func (s *Store) Get(ctx context.Context, id string) ([]byte, error) {
	var data []byte
	err := s.DB.QueryRow(ctx, `SELECT data FROM attachments WHERE id = $1`, id).Scan(&data)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	return data, err
}

// DeleteExpired removes blobs older than Retention.
func (s *Store) DeleteExpired(ctx context.Context) (int64, error) {
	tag, err := s.DB.Exec(ctx, `DELETE FROM attachments WHERE created_at < $1`, time.Now().Add(-Retention))
	return tag.RowsAffected(), err
}

// RunCleanup deletes expired blobs every hour until ctx ends.
func (s *Store) RunCleanup(ctx context.Context) {
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	for {
		if n, err := s.DeleteExpired(ctx); err != nil {
			slog.Error("attachment cleanup failed", "err", err)
		} else if n > 0 {
			slog.Info("deleted expired attachments", "count", n)
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

type Handlers struct {
	Store *Store
	Auth  *auth.Handlers
}

func (h *Handlers) Register(mux *http.ServeMux) {
	mux.Handle("POST /v1/attachments", h.Auth.Require(http.HandlerFunc(h.upload)))
	mux.Handle("GET /v1/attachments/{id}", h.Auth.Require(http.HandlerFunc(h.download)))
}

func (h *Handlers) upload(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, MaxSize))
	var tooBig *http.MaxBytesError
	switch {
	case errors.As(err, &tooBig):
		httpx.Error(w, http.StatusRequestEntityTooLarge, "too_large", "files can be at most 16 MB")
		return
	case err != nil:
		httpx.Error(w, http.StatusBadRequest, "bad_body", "could not read upload")
		return
	case len(data) == 0:
		httpx.Error(w, http.StatusBadRequest, "empty", "upload is empty")
		return
	}
	id, err := h.Store.Put(r.Context(), auth.UserID(r.Context()), data)
	if err != nil {
		slog.Error("store attachment failed", "err", err)
		httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
		return
	}
	httpx.JSON(w, http.StatusCreated, map[string]any{"id": id, "size": len(data)})
}

// download serves a blob to any signed-in user who knows its random ID.
// The content is encrypted with a key only the chat's members have.
func (h *Handlers) download(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !auth.IsUUID(id) {
		httpx.Error(w, http.StatusNotFound, "not_found", "attachment not found")
		return
	}
	data, err := h.Store.Get(r.Context(), id)
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusNotFound, "not_found", "attachment expired or does not exist")
		return
	}
	if err != nil {
		slog.Error("load attachment failed", "err", err)
		httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	w.Write(data)
}
