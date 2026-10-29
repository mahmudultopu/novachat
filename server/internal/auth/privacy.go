package auth

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"

	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

// Who may see part of a profile.
const (
	Everyone = "everyone"
	Chats    = "chats" // people the user has messaged
	Nobody   = "nobody"
)

// Privacy is what a user shares, and with whom.
type Privacy struct {
	LastSeen     string `json:"last_seen"`
	Photo        string `json:"photo"`
	About        string `json:"about"`
	ReadReceipts bool   `json:"read_receipts"`
}

// visible is SQL that is true when the viewer (a SQL parameter such as $2)
// may see the part of user u that column (such as photo_privacy) guards.
func visible(viewer, column string) string {
	return fmt.Sprintf(`(u.id = %[1]s OR (
		NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = u.id AND b.blocked_id = %[1]s)
		AND (u.%[2]s = 'everyone' OR (u.%[2]s = 'chats' AND EXISTS (
			SELECT 1 FROM messages pm WHERE pm.sender_id = u.id AND pm.recipient_id = %[1]s AND pm.group_id IS NULL)))))`,
		viewer, column)
}

// VisibleCols selects user u as the viewer (a SQL parameter) may see them:
// the same columns as userCols, with hidden parts left empty.
func VisibleCols(viewer string) string {
	return `u.id::text, u.phone, u.name,
		CASE WHEN ` + visible(viewer, "about_privacy") + ` THEN u.about ELSE '' END, u.created_at,
		CASE WHEN ` + visible(viewer, "photo_privacy") + ` THEN u.avatar_id::text END,
		CASE WHEN ` + visible(viewer, "last_seen_privacy") + ` THEN u.last_seen_at END`
}

// UserFor returns user id as viewerID may see them.
func (s *Store) UserFor(ctx context.Context, viewerID, id string) (User, error) {
	if !IsUUID(id) {
		return User{}, ErrNotFound
	}
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+VisibleCols("$2")+` FROM users u WHERE u.id = $1`, id, viewerID))
}

// UserByPhoneFor finds a user by phone number, as viewerID may see them.
func (s *Store) UserByPhoneFor(ctx context.Context, viewerID, phone string) (User, error) {
	return scanUser(s.DB.QueryRow(ctx, `SELECT `+VisibleCols("$2")+` FROM users u WHERE u.phone = $1`, phone, viewerID))
}

// CanSeeLastSeen reports whether viewerID may see when id was last online,
// and so whether they are online now.
func (s *Store) CanSeeLastSeen(ctx context.Context, viewerID, id string) (bool, error) {
	var ok bool
	err := s.DB.QueryRow(ctx, `SELECT `+visible("$2", "last_seen_privacy")+` FROM users u WHERE u.id = $1`, id, viewerID).Scan(&ok)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	return ok, err
}

func (s *Store) Privacy(ctx context.Context, userID string) (Privacy, error) {
	var p Privacy
	err := s.DB.QueryRow(ctx, `SELECT last_seen_privacy, photo_privacy, about_privacy, read_receipts FROM users WHERE id = $1`, userID).
		Scan(&p.LastSeen, &p.Photo, &p.About, &p.ReadReceipts)
	return p, err
}

// SetPrivacy changes the settings that are not nil.
func (s *Store) SetPrivacy(ctx context.Context, userID string, lastSeen, photo, about *string, readReceipts *bool) (Privacy, error) {
	var p Privacy
	err := s.DB.QueryRow(ctx, `
		UPDATE users SET last_seen_privacy = COALESCE($2, last_seen_privacy), photo_privacy = COALESCE($3, photo_privacy),
		       about_privacy = COALESCE($4, about_privacy), read_receipts = COALESCE($5, read_receipts)
		WHERE id = $1 RETURNING last_seen_privacy, photo_privacy, about_privacy, read_receipts`,
		userID, lastSeen, photo, about, readReceipts).Scan(&p.LastSeen, &p.Photo, &p.About, &p.ReadReceipts)
	return p, err
}

// Block stops blockedID seeing blockerID's profile and presence.
func (s *Store) Block(ctx context.Context, blockerID, blockedID string) error {
	tag, err := s.DB.Exec(ctx, `
		INSERT INTO blocks (blocker_id, blocked_id) SELECT $1, id FROM users WHERE id = $2
		ON CONFLICT DO NOTHING`, blockerID, blockedID)
	if err == nil && tag.RowsAffected() == 0 {
		var exists bool
		if err := s.DB.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM users WHERE id = $1)`, blockedID).Scan(&exists); err != nil {
			return err
		}
		if !exists {
			return ErrNotFound
		}
	}
	return err
}

func (s *Store) Unblock(ctx context.Context, blockerID, blockedID string) error {
	_, err := s.DB.Exec(ctx, `DELETE FROM blocks WHERE blocker_id = $1 AND blocked_id = $2`, blockerID, blockedID)
	return err
}

// Blocked lists the users blockerID has blocked, most recent first.
func (s *Store) Blocked(ctx context.Context, blockerID string) ([]User, error) {
	rows, err := s.DB.Query(ctx, `
		SELECT `+VisibleCols("$1")+` FROM blocks b JOIN users u ON u.id = b.blocked_id
		WHERE b.blocker_id = $1 ORDER BY b.created_at DESC`, blockerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []User{}
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// HasBlocked reports whether blockerID blocked blockedID.
func (s *Store) HasBlocked(ctx context.Context, blockerID, blockedID string) (bool, error) {
	var ok bool
	err := s.DB.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM blocks WHERE blocker_id = $1 AND blocked_id = $2)`, blockerID, blockedID).Scan(&ok)
	return ok, err
}

func (s *Store) Report(ctx context.Context, reporterID, reportedID, reason string) error {
	tag, err := s.DB.Exec(ctx, `INSERT INTO reports (reporter_id, reported_id, reason) SELECT $1, id, $3 FROM users WHERE id = $2`,
		reporterID, reportedID, reason)
	if err == nil && tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return err
}

func validLevel(p *string) bool {
	return p == nil || *p == Everyone || *p == Chats || *p == Nobody
}

func (h *Handlers) privacy(w http.ResponseWriter, r *http.Request) {
	p, err := h.Store.Privacy(r.Context(), UserID(r.Context()))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, p)
}

func (h *Handlers) updatePrivacy(w http.ResponseWriter, r *http.Request) {
	var req struct {
		LastSeen     *string `json:"last_seen"`
		Photo        *string `json:"photo"`
		About        *string `json:"about"`
		ReadReceipts *bool   `json:"read_receipts"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	if !validLevel(req.LastSeen) || !validLevel(req.Photo) || !validLevel(req.About) {
		httpx.Error(w, http.StatusBadRequest, "invalid_privacy", `use "everyone", "chats" or "nobody"`)
		return
	}
	p, err := h.Store.SetPrivacy(r.Context(), UserID(r.Context()), req.LastSeen, req.Photo, req.About, req.ReadReceipts)
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, p)
}

func (h *Handlers) blocked(w http.ResponseWriter, r *http.Request) {
	users, err := h.Store.Blocked(r.Context(), UserID(r.Context()))
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, users)
}

func (h *Handlers) block(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !IsUUID(id) || id == UserID(r.Context()) {
		httpx.Error(w, http.StatusBadRequest, "bad_user", "user id is not valid")
		return
	}
	err := h.Store.Block(r.Context(), UserID(r.Context()), id)
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusNotFound, "not_found", "user not found")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) unblock(w http.ResponseWriter, r *http.Request) {
	if id := r.PathValue("id"); IsUUID(id) {
		if err := h.Store.Unblock(r.Context(), UserID(r.Context()), id); err != nil {
			internalError(w, err)
			return
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

// report records a report, and blocks the user too when asked.
func (h *Handlers) report(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UserID string `json:"user_id"`
		Reason string `json:"reason"`
		Block  bool   `json:"block"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	me := UserID(r.Context())
	req.Reason = strings.TrimSpace(req.Reason)
	switch {
	case !IsUUID(req.UserID) || req.UserID == me:
		httpx.Error(w, http.StatusBadRequest, "bad_user", "user id is not valid")
		return
	case req.Reason == "" || utf8.RuneCountInString(req.Reason) > 500:
		httpx.Error(w, http.StatusBadRequest, "bad_reason", "reason must be 1 to 500 characters")
		return
	}
	err := h.Store.Report(r.Context(), me, req.UserID, req.Reason)
	if err == nil && req.Block {
		err = h.Store.Block(r.Context(), me, req.UserID)
	}
	if errors.Is(err, ErrNotFound) {
		httpx.Error(w, http.StatusNotFound, "not_found", "user not found")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
