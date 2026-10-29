// Package groups manages group chats: members, admins, settings and invite
// links. Group messages themselves never pass through here: phones encrypt
// a copy for each member and send it as an ordinary 1:1 message. Every
// change is written as an event message to the members, so their phones
// update the group and show "Ana added Ben" in order with the chat.
package groups

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/chat"
	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

const (
	MaxMembers     = 256
	maxNameRunes   = 50
	maxDescRunes   = 500
	maxAvatarBytes = 512 << 10
)

type Member struct {
	User     auth.User `json:"user"`
	Role     string    `json:"role"` // "admin" or "member"
	JoinedAt time.Time `json:"joined_at"`
}

type Group struct {
	ID             string    `json:"id"`
	Name           string    `json:"name"`
	Description    string    `json:"description"`
	AvatarID       *string   `json:"avatar_id,omitempty"`
	CreatedBy      *string   `json:"created_by,omitempty"`
	CreatedAt      time.Time `json:"created_at"`
	OnlyAdminsSend bool      `json:"only_admins_send"`
	OnlyAdminsEdit bool      `json:"only_admins_edit"`
	// InviteCode is only shown to admins.
	InviteCode *string  `json:"invite_code,omitempty"`
	Members    []Member `json:"members"`
}

// Event is the body of a group event message.
type Event struct {
	Event   string   `json:"event"` // created, added, removed, left, joined, promoted, demoted, renamed, description, photo, settings
	Actor   string   `json:"actor"`
	Targets []string `json:"targets,omitempty"`
	Name    string   `json:"name,omitempty"`
}

var (
	errNotFound  = errors.New("group not found")
	errForbidden = errors.New("only group admins can do that")
)

type Handlers struct {
	DB   *pgxpool.Pool
	Hub  *chat.Hub
	Auth *auth.Handlers
	// APKURL is linked from invite pages for people without the app.
	APKURL string
}

func (h *Handlers) Register(mux *http.ServeMux) {
	req := func(f http.HandlerFunc) http.Handler { return h.Auth.Require(f) }
	mux.Handle("POST /v1/groups", req(h.create))
	mux.Handle("GET /v1/groups", req(h.list))
	mux.Handle("GET /v1/groups/{id}", req(h.get))
	mux.Handle("PATCH /v1/groups/{id}", req(h.update))
	mux.Handle("PUT /v1/groups/{id}/avatar", req(h.setAvatar))
	mux.Handle("DELETE /v1/groups/{id}/avatar", req(h.removeAvatar))
	mux.Handle("POST /v1/groups/{id}/members", req(h.addMembers))
	mux.Handle("DELETE /v1/groups/{id}/members/{uid}", req(h.removeMember))
	mux.Handle("PUT /v1/groups/{id}/members/{uid}", req(h.setRole))
	mux.Handle("POST /v1/groups/{id}/invite", req(h.resetInvite))
	mux.Handle("DELETE /v1/groups/{id}/invite", req(h.revokeInvite))
	mux.Handle("GET /v1/invites/{code}", req(h.previewInvite))
	mux.Handle("POST /v1/invites/{code}/join", req(h.joinInvite))
	mux.HandleFunc("GET /join/{code}", h.invitePage)
}

// change runs fn in a transaction, writes the events it returns to the
// group's members (plus anyone in extra, such as a removed member), then
// tells their phones and answers with the group as the caller now sees it.
func (h *Handlers) change(w http.ResponseWriter, r *http.Request, groupID string,
	fn func(tx pgx.Tx, me string) ([]Event, []string, error)) {
	ctx := r.Context()
	me := auth.UserID(ctx)
	tx, err := h.DB.Begin(ctx)
	if err != nil {
		internalError(w, err)
		return
	}
	defer tx.Rollback(ctx)
	events, extra, err := fn(tx, me)
	if err != nil {
		writeErr(w, err)
		return
	}
	members, err := memberIDs(ctx, tx, groupID)
	if err != nil {
		internalError(w, err)
		return
	}
	var sent []chat.Message
	for _, e := range events {
		body, _ := json.Marshal(e)
		msgs, err := chat.InsertEvents(ctx, tx, groupID, e.Actor, union(members, extra), string(body))
		if err != nil {
			internalError(w, err)
			return
		}
		sent = append(sent, msgs...)
	}
	if len(members) == 0 {
		// The last member left: nobody can see the group any more.
		if _, err := tx.Exec(ctx, `DELETE FROM groups WHERE id = $1`, groupID); err != nil {
			internalError(w, err)
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		internalError(w, err)
		return
	}
	for _, m := range sent {
		b, _ := json.Marshal(struct {
			Type    string        `json:"type"`
			Message *chat.Message `json:"message"`
		}{"message", &m})
		h.Hub.Publish(ctx, m.RecipientID, b)
	}
	g, err := load(ctx, h.DB, groupID, me)
	if errors.Is(err, errNotFound) {
		w.WriteHeader(http.StatusNoContent) // e.g. after leaving
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, g)
}

func (h *Handlers) create(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name        string   `json:"name"`
		Description string   `json:"description"`
		Members     []string `json:"members"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	name, ok := validName(w, req.Name)
	if !ok || !validDesc(w, req.Description) {
		return
	}
	others, ok := validIDs(w, req.Members, auth.UserID(r.Context()))
	if !ok {
		return
	}
	if len(others)+1 > MaxMembers {
		httpx.Error(w, http.StatusBadRequest, "too_many_members", fmt.Sprintf("groups can have at most %d members", MaxMembers))
		return
	}
	var id string
	ctx := r.Context()
	me := auth.UserID(ctx)
	tx, err := h.DB.Begin(ctx)
	if err != nil {
		internalError(w, err)
		return
	}
	defer tx.Rollback(ctx)
	if err := tx.QueryRow(ctx, `INSERT INTO groups (name, description, created_by) VALUES ($1, $2, $3) RETURNING id::text`,
		name, strings.TrimSpace(req.Description), me).Scan(&id); err != nil {
		internalError(w, err)
		return
	}
	if _, err := tx.Exec(ctx, `INSERT INTO group_members (group_id, user_id, role) VALUES ($1, $2, 'admin')`, id, me); err != nil {
		internalError(w, err)
		return
	}
	if err := insertMembers(ctx, tx, id, others); err != nil {
		writeErr(w, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		internalError(w, err)
		return
	}
	// The "created" event goes out like any other change.
	h.change(w, r, id, func(pgx.Tx, string) ([]Event, []string, error) {
		return []Event{{Event: "created", Actor: me, Targets: others, Name: name}}, nil, nil
	})
}

func (h *Handlers) list(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserID(ctx)
	rows, err := h.DB.Query(ctx, `SELECT group_id::text FROM group_members WHERE user_id = $1`, me)
	if err != nil {
		internalError(w, err)
		return
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		internalError(w, err)
		return
	}
	out := []Group{}
	for _, id := range ids {
		g, err := load(ctx, h.DB, id, me)
		if errors.Is(err, errNotFound) {
			continue
		}
		if err != nil {
			internalError(w, err)
			return
		}
		out = append(out, g)
	}
	httpx.JSON(w, http.StatusOK, out)
}

func (h *Handlers) get(w http.ResponseWriter, r *http.Request) {
	g, err := load(r.Context(), h.DB, r.PathValue("id"), auth.UserID(r.Context()))
	if err != nil {
		writeErr(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, g)
}

func (h *Handlers) update(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Name           *string `json:"name"`
		Description    *string `json:"description"`
		OnlyAdminsSend *bool   `json:"only_admins_send"`
		OnlyAdminsEdit *bool   `json:"only_admins_edit"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	if req.Name != nil {
		n, ok := validName(w, *req.Name)
		if !ok {
			return
		}
		req.Name = &n
	}
	if req.Description != nil {
		if !validDesc(w, *req.Description) {
			return
		}
		d := strings.TrimSpace(*req.Description)
		req.Description = &d
	}
	id := r.PathValue("id")
	h.change(w, r, id, func(tx pgx.Tx, me string) ([]Event, []string, error) {
		admin, onlyAdminsEdit, err := access(r.Context(), tx, id, me)
		if err != nil {
			return nil, nil, err
		}
		settings := req.OnlyAdminsSend != nil || req.OnlyAdminsEdit != nil
		if (settings || onlyAdminsEdit) && !admin {
			return nil, nil, errForbidden
		}
		var events []Event
		if req.Name != nil {
			events = append(events, Event{Event: "renamed", Actor: me, Name: *req.Name})
		}
		if req.Description != nil {
			events = append(events, Event{Event: "description", Actor: me})
		}
		if settings {
			events = append(events, Event{Event: "settings", Actor: me})
		}
		_, err = tx.Exec(r.Context(), `
			UPDATE groups SET name = COALESCE($2, name), description = COALESCE($3, description),
			       only_admins_send = COALESCE($4, only_admins_send), only_admins_edit = COALESCE($5, only_admins_edit)
			WHERE id = $1`, id, req.Name, req.Description, req.OnlyAdminsSend, req.OnlyAdminsEdit)
		return events, nil, err
	})
}

func (h *Handlers) setAvatar(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxAvatarBytes))
	var tooBig *http.MaxBytesError
	switch {
	case errors.As(err, &tooBig):
		httpx.Error(w, http.StatusRequestEntityTooLarge, "too_large", "group photos can be at most 512 KB")
		return
	case err != nil:
		httpx.Error(w, http.StatusBadRequest, "bad_body", "could not read upload")
		return
	case len(data) < 3 || data[0] != 0xFF || data[1] != 0xD8 || data[2] != 0xFF:
		httpx.Error(w, http.StatusBadRequest, "not_jpeg", "group photos must be JPEG")
		return
	}
	h.photo(w, r, data)
}

func (h *Handlers) removeAvatar(w http.ResponseWriter, r *http.Request) { h.photo(w, r, nil) }

// photo replaces (or with nil data removes) the group photo.
func (h *Handlers) photo(w http.ResponseWriter, r *http.Request, data []byte) {
	id := r.PathValue("id")
	h.change(w, r, id, func(tx pgx.Tx, me string) ([]Event, []string, error) {
		ctx := r.Context()
		admin, onlyAdminsEdit, err := access(ctx, tx, id, me)
		if err != nil {
			return nil, nil, err
		}
		if onlyAdminsEdit && !admin {
			return nil, nil, errForbidden
		}
		var old, next *string
		if err := tx.QueryRow(ctx, `SELECT avatar_id::text FROM groups WHERE id = $1`, id).Scan(&old); err != nil {
			return nil, nil, err
		}
		if data != nil {
			var aid string
			if err := tx.QueryRow(ctx, `INSERT INTO avatars (user_id, data) VALUES ($1, $2) RETURNING id::text`, me, data).Scan(&aid); err != nil {
				return nil, nil, err
			}
			next = &aid
		}
		if _, err := tx.Exec(ctx, `UPDATE groups SET avatar_id = $2 WHERE id = $1`, id, next); err != nil {
			return nil, nil, err
		}
		if old != nil {
			if _, err := tx.Exec(ctx, `DELETE FROM avatars WHERE id = $1`, *old); err != nil {
				return nil, nil, err
			}
		}
		return []Event{{Event: "photo", Actor: me}}, nil, nil
	})
}

func (h *Handlers) addMembers(w http.ResponseWriter, r *http.Request) {
	var req struct {
		UserIDs []string `json:"user_ids"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	others, ok := validIDs(w, req.UserIDs, auth.UserID(r.Context()))
	if !ok {
		return
	}
	id := r.PathValue("id")
	h.change(w, r, id, func(tx pgx.Tx, me string) ([]Event, []string, error) {
		ctx := r.Context()
		if err := requireAdmin(ctx, tx, id, me); err != nil {
			return nil, nil, err
		}
		existing, err := memberIDs(ctx, tx, id)
		if err != nil {
			return nil, nil, err
		}
		fresh := minus(others, existing)
		if len(fresh) == 0 {
			return nil, nil, nil
		}
		if len(existing)+len(fresh) > MaxMembers {
			return nil, nil, badRequest{"too_many_members", fmt.Sprintf("groups can have at most %d members", MaxMembers)}
		}
		if err := insertMembers(ctx, tx, id, fresh); err != nil {
			return nil, nil, err
		}
		return []Event{{Event: "added", Actor: me, Targets: fresh}}, nil, nil
	})
}

// removeMember removes someone (admins only), or leaves when uid is you.
func (h *Handlers) removeMember(w http.ResponseWriter, r *http.Request) {
	id, uid := r.PathValue("id"), r.PathValue("uid")
	h.change(w, r, id, func(tx pgx.Tx, me string) ([]Event, []string, error) {
		ctx := r.Context()
		if uid == me {
			if _, _, err := access(ctx, tx, id, me); err != nil {
				return nil, nil, err
			}
		} else if err := requireAdmin(ctx, tx, id, me); err != nil {
			return nil, nil, err
		}
		tag, err := tx.Exec(ctx, `DELETE FROM group_members WHERE group_id = $1 AND user_id = $2`, id, uid)
		if err != nil {
			return nil, nil, err
		}
		if tag.RowsAffected() == 0 {
			return nil, nil, errNotFound
		}
		events := []Event{{Event: "removed", Actor: me, Targets: []string{uid}}}
		if uid == me {
			events = []Event{{Event: "left", Actor: me}}
		}
		// A group always keeps an admin: the longest-standing member takes over.
		var promoted string
		err = tx.QueryRow(ctx, `
			UPDATE group_members SET role = 'admin'
			WHERE group_id = $1 AND user_id = (
				SELECT user_id FROM group_members WHERE group_id = $1 ORDER BY joined_at LIMIT 1)
			  AND NOT EXISTS (SELECT 1 FROM group_members WHERE group_id = $1 AND role = 'admin')
			RETURNING user_id::text`, id).Scan(&promoted)
		if err == nil {
			events = append(events, Event{Event: "promoted", Actor: me, Targets: []string{promoted}})
		} else if !errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, err
		}
		return events, []string{uid}, nil
	})
}

func (h *Handlers) setRole(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Role string `json:"role"`
	}
	if !httpx.Decode(w, r, &req) {
		return
	}
	if req.Role != "admin" && req.Role != "member" {
		httpx.Error(w, http.StatusBadRequest, "bad_role", `role must be "admin" or "member"`)
		return
	}
	id, uid := r.PathValue("id"), r.PathValue("uid")
	h.change(w, r, id, func(tx pgx.Tx, me string) ([]Event, []string, error) {
		ctx := r.Context()
		if err := requireAdmin(ctx, tx, id, me); err != nil {
			return nil, nil, err
		}
		if uid == me && req.Role == "member" {
			var admins int
			if err := tx.QueryRow(ctx, `SELECT count(*) FROM group_members WHERE group_id = $1 AND role = 'admin'`, id).Scan(&admins); err != nil {
				return nil, nil, err
			}
			if admins == 1 {
				return nil, nil, badRequest{"last_admin", "make someone else an admin first"}
			}
		}
		tag, err := tx.Exec(ctx, `UPDATE group_members SET role = $3 WHERE group_id = $1 AND user_id = $2 AND role <> $3`, id, uid, req.Role)
		if err != nil {
			return nil, nil, err
		}
		if tag.RowsAffected() == 0 {
			return nil, nil, nil // not a member, or no change
		}
		event := "promoted"
		if req.Role == "member" {
			event = "demoted"
		}
		return []Event{{Event: event, Actor: me, Targets: []string{uid}}}, nil, nil
	})
}

func (h *Handlers) resetInvite(w http.ResponseWriter, r *http.Request) {
	h.setInvite(w, r, true)
}

func (h *Handlers) revokeInvite(w http.ResponseWriter, r *http.Request) {
	h.setInvite(w, r, false)
}

func (h *Handlers) setInvite(w http.ResponseWriter, r *http.Request, on bool) {
	id := r.PathValue("id")
	h.change(w, r, id, func(tx pgx.Tx, me string) ([]Event, []string, error) {
		ctx := r.Context()
		if err := requireAdmin(ctx, tx, id, me); err != nil {
			return nil, nil, err
		}
		var code *string
		if on {
			c := newInviteCode()
			code = &c
		}
		_, err := tx.Exec(ctx, `UPDATE groups SET invite_code = $2 WHERE id = $1`, id, code)
		return nil, nil, err
	})
}

type invitePreview struct {
	ID          string  `json:"id"`
	Name        string  `json:"name"`
	Description string  `json:"description"`
	AvatarID    *string `json:"avatar_id,omitempty"`
	MemberCount int     `json:"member_count"`
	IsMember    bool    `json:"is_member"`
}

func (h *Handlers) previewInvite(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var p invitePreview
	err := h.DB.QueryRow(ctx, `
		SELECT g.id::text, g.name, g.description, g.avatar_id::text,
		       (SELECT count(*) FROM group_members m WHERE m.group_id = g.id),
		       EXISTS (SELECT 1 FROM group_members m WHERE m.group_id = g.id AND m.user_id = $2)
		FROM groups g WHERE g.invite_code = $1`, r.PathValue("code"), auth.UserID(ctx)).
		Scan(&p.ID, &p.Name, &p.Description, &p.AvatarID, &p.MemberCount, &p.IsMember)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Error(w, http.StatusNotFound, "invalid_invite", "this invite link is invalid or was reset")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	httpx.JSON(w, http.StatusOK, p)
}

func (h *Handlers) joinInvite(w http.ResponseWriter, r *http.Request) {
	var id string
	err := h.DB.QueryRow(r.Context(), `SELECT id::text FROM groups WHERE invite_code = $1`, r.PathValue("code")).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Error(w, http.StatusNotFound, "invalid_invite", "this invite link is invalid or was reset")
		return
	}
	if err != nil {
		internalError(w, err)
		return
	}
	h.change(w, r, id, func(tx pgx.Tx, me string) ([]Event, []string, error) {
		ctx := r.Context()
		existing, err := memberIDs(ctx, tx, id)
		if err != nil {
			return nil, nil, err
		}
		if contains(existing, me) {
			return nil, nil, nil
		}
		if len(existing) >= MaxMembers {
			return nil, nil, badRequest{"group_full", "this group is full"}
		}
		if err := insertMembers(ctx, tx, id, []string{me}); err != nil {
			return nil, nil, err
		}
		return []Event{{Event: "joined", Actor: me}}, nil, nil
	})
}

var invitePage = template.Must(template.New("invite").Parse(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Join a group on NovaChat</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#F4F2FB;color:#1B1530;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{background:#fff;border-radius:24px;padding:32px 24px;max-width:360px;margin:16px;text-align:center;box-shadow:0 4px 24px rgba(91,61,245,.12)}
h1{font-size:22px;margin:0 0 8px}p{color:#5d5873;line-height:1.5}
a.btn{display:block;margin-top:16px;padding:14px;border-radius:16px;text-decoration:none;font-weight:600}
.primary{background:#5B3DF5;color:#fff}.secondary{background:#EEEAFD;color:#5B3DF5}
</style></head><body><main>
<h1>You're invited to a group</h1>
<p>Open this link on your Android phone to join the group in NovaChat. Messages in NovaChat groups are end-to-end encrypted.</p>
<a class="btn primary" href="novachat://join/{{.Code}}">Open in NovaChat</a>
{{if .APK}}<a class="btn secondary" href="{{.APK}}">Don't have NovaChat? Download it</a>{{end}}
</main></body></html>`))

// invitePage is what an invite link opens in a browser: a button into the
// app. It does not show the group's name, so a leaked link reveals nothing.
func (h *Handlers) invitePage(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if len(code) == 0 || len(code) > 64 {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_ = invitePage.Execute(w, struct{ Code, APK string }{code, h.APKURL})
}

// load returns the group as userID sees it, or errNotFound if they are not in it.
func load(ctx context.Context, q interface {
	QueryRow(context.Context, string, ...any) pgx.Row
	Query(context.Context, string, ...any) (pgx.Rows, error)
}, groupID, userID string) (Group, error) {
	var g Group
	if !auth.IsUUID(groupID) {
		return g, errNotFound
	}
	var myRole *string
	err := q.QueryRow(ctx, `
		SELECT g.id::text, g.name, g.description, g.avatar_id::text, g.created_by::text, g.created_at,
		       g.only_admins_send, g.only_admins_edit, g.invite_code,
		       (SELECT role FROM group_members WHERE group_id = g.id AND user_id = $2)
		FROM groups g WHERE g.id = $1`, groupID, userID).
		Scan(&g.ID, &g.Name, &g.Description, &g.AvatarID, &g.CreatedBy, &g.CreatedAt,
			&g.OnlyAdminsSend, &g.OnlyAdminsEdit, &g.InviteCode, &myRole)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && myRole == nil) {
		return g, errNotFound
	}
	if err != nil {
		return g, err
	}
	if *myRole != "admin" {
		g.InviteCode = nil
	}
	rows, err := q.Query(ctx, `
		SELECT u.id::text, u.phone, u.name, u.about, u.created_at, u.avatar_id::text, u.last_seen_at, m.role, m.joined_at
		FROM group_members m JOIN users u ON u.id = m.user_id
		WHERE m.group_id = $1 ORDER BY m.joined_at`, groupID)
	if err != nil {
		return g, err
	}
	defer rows.Close()
	g.Members = []Member{}
	for rows.Next() {
		var m Member
		u := &m.User
		if err := rows.Scan(&u.ID, &u.Phone, &u.Name, &u.About, &u.CreatedAt, &u.AvatarID, &u.LastSeenAt, &m.Role, &m.JoinedAt); err != nil {
			return g, err
		}
		g.Members = append(g.Members, m)
	}
	return g, rows.Err()
}

// access reports whether userID is an admin of the group, and whether only
// admins may edit its info. A non-member gets errNotFound.
func access(ctx context.Context, tx pgx.Tx, groupID, userID string) (admin, onlyAdminsEdit bool, err error) {
	if !auth.IsUUID(groupID) {
		return false, false, errNotFound
	}
	var role string
	err = tx.QueryRow(ctx, `
		SELECT m.role, g.only_admins_edit FROM group_members m JOIN groups g ON g.id = m.group_id
		WHERE m.group_id = $1 AND m.user_id = $2 FOR UPDATE OF g`, groupID, userID).Scan(&role, &onlyAdminsEdit)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, false, errNotFound
	}
	return role == "admin", onlyAdminsEdit, err
}

func requireAdmin(ctx context.Context, tx pgx.Tx, groupID, userID string) error {
	admin, _, err := access(ctx, tx, groupID, userID)
	if err == nil && !admin {
		return errForbidden
	}
	return err
}

func memberIDs(ctx context.Context, tx pgx.Tx, groupID string) ([]string, error) {
	rows, err := tx.Query(ctx, `SELECT user_id::text FROM group_members WHERE group_id = $1`, groupID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

func insertMembers(ctx context.Context, tx pgx.Tx, groupID string, ids []string) error {
	_, err := tx.Exec(ctx, `
		INSERT INTO group_members (group_id, user_id) SELECT $1, u FROM unnest($2::uuid[]) AS u
		ON CONFLICT DO NOTHING`, groupID, ids)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23503" {
		return badRequest{"unknown_user", "one of those people is not on NovaChat"}
	}
	return err
}

func newInviteCode() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func validName(w http.ResponseWriter, name string) (string, bool) {
	n := strings.TrimSpace(name)
	if n == "" || utf8.RuneCountInString(n) > maxNameRunes {
		httpx.Error(w, http.StatusBadRequest, "invalid_name", fmt.Sprintf("group names must be 1 to %d characters", maxNameRunes))
		return "", false
	}
	return n, true
}

func validDesc(w http.ResponseWriter, d string) bool {
	if utf8.RuneCountInString(d) > maxDescRunes {
		httpx.Error(w, http.StatusBadRequest, "invalid_description", fmt.Sprintf("descriptions can be at most %d characters", maxDescRunes))
		return false
	}
	return true
}

// validIDs checks a list of user IDs and drops duplicates and the caller.
func validIDs(w http.ResponseWriter, ids []string, me string) ([]string, bool) {
	seen := map[string]bool{me: true}
	out := []string{}
	for _, id := range ids {
		if !auth.IsUUID(id) {
			httpx.Error(w, http.StatusBadRequest, "bad_user_id", "members must be user ids")
			return nil, false
		}
		id = strings.ToLower(id)
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	if len(out) >= MaxMembers {
		httpx.Error(w, http.StatusBadRequest, "too_many_members", fmt.Sprintf("groups can have at most %d members", MaxMembers))
		return nil, false
	}
	return out, true
}

type badRequest struct{ code, msg string }

func (e badRequest) Error() string { return e.msg }

func writeErr(w http.ResponseWriter, err error) {
	var br badRequest
	switch {
	case errors.Is(err, errNotFound):
		httpx.Error(w, http.StatusNotFound, "not_found", "group not found")
	case errors.Is(err, errForbidden):
		httpx.Error(w, http.StatusForbidden, "not_admin", err.Error())
	case errors.As(err, &br):
		httpx.Error(w, http.StatusBadRequest, br.code, br.msg)
	default:
		internalError(w, err)
	}
}

func internalError(w http.ResponseWriter, err error) {
	slog.Error("group request failed", "err", err)
	httpx.Error(w, http.StatusInternalServerError, "internal", "something went wrong")
}

func union(a, b []string) []string {
	out := append([]string{}, a...)
	for _, x := range b {
		if !contains(out, x) {
			out = append(out, x)
		}
	}
	return out
}

func minus(a, b []string) []string {
	var out []string
	for _, x := range a {
		if !contains(b, x) {
			out = append(out, x)
		}
	}
	return out
}

func contains(list []string, x string) bool {
	for _, y := range list {
		if y == x {
			return true
		}
	}
	return false
}
