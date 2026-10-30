package auth

import (
	"context"
	"net/http"

	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

// MaxContactPhones caps one match request, enough for a big address book.
const MaxContactPhones = 5000

// MatchPhones returns the users with these phone numbers, as viewerID may see
// them, leaving out viewerID. Numbers are only compared, never stored.
func (s *Store) MatchPhones(ctx context.Context, viewerID string, phones []string) ([]User, error) {
	rows, err := s.DB.Query(ctx, `SELECT `+VisibleCols("$2")+` FROM users u
		WHERE u.phone = ANY($1) AND u.id <> $2 ORDER BY u.name`, phones, viewerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	users := []User{}
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		users = append(users, u)
	}
	return users, rows.Err()
}

// matchContacts answers "which of my contacts use NovaChat?".
func (h *Handlers) matchContacts(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Phones []string `json:"phones"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	if len(req.Phones) > MaxContactPhones {
		httpx.Error(w, http.StatusBadRequest, "too_many", "send at most 5000 numbers at once")
		return
	}
	// Skip numbers that aren't valid rather than failing the whole address book.
	seen := map[string]bool{}
	phones := []string{}
	for _, p := range req.Phones {
		if n, err := NormalizePhone(p); err == nil && !seen[n] {
			seen[n] = true
			phones = append(phones, n)
		}
	}
	users, err := h.Store.MatchPhones(r.Context(), UserID(r.Context()), phones)
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"users": users})
}
