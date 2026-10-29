package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/mahmudultopu/novachat/server/internal/config"
	"github.com/mahmudultopu/novachat/server/internal/db"
)

// testServer starts the whole server against the test database and Redis,
// or skips the test when they are not configured.
func testServer(t *testing.T) string {
	t.Helper()
	dbURL, redisURL := os.Getenv("NOVACHAT_TEST_DATABASE_URL"), os.Getenv("NOVACHAT_TEST_REDIS_URL")
	if dbURL == "" || redisURL == "" {
		t.Skip("set NOVACHAT_TEST_DATABASE_URL and NOVACHAT_TEST_REDIS_URL to run")
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	pool, err := db.Open(ctx, dbURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	ropts, _ := redis.ParseURL(redisURL)
	rdb := redis.NewClient(ropts)
	t.Cleanup(func() { rdb.Close() })
	handler, closeHub := NewHandler(ctx, config.Config{Env: "dev", DevOTP: "123456", STUNURLs: []string{"stun:stun.example.com:3478"}}, pool, rdb)
	t.Cleanup(closeHub)
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return srv.URL
}

type groupResp struct {
	ID             string
	Name           string
	Description    string
	InviteCode     *string `json:"invite_code"`
	OnlyAdminsSend bool    `json:"only_admins_send"`
	Members        []struct {
		User struct{ ID, Name string }
		Role string
	}
}

func (g groupResp) role(id string) string {
	for _, m := range g.Members {
		if m.User.ID == id {
			return m.Role
		}
	}
	return ""
}

type groupEvent struct {
	Event   string
	Actor   string
	Targets []string
	Name    string
}

// readEvent waits for the next group event of kind want on a socket,
// skipping other frames and events.
func readEvent(t *testing.T, c interface {
	SetReadDeadline(time.Time) error
	ReadJSON(any) error
}, groupID, want string) groupEvent {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		var f struct {
			Type    string
			Message *struct {
				Body    string
				GroupID *string `json:"group_id"`
			}
		}
		if err := c.ReadJSON(&f); err != nil {
			t.Fatalf("waiting for group event: %v", err)
		}
		if f.Type == "message" && f.Message.GroupID != nil && *f.Message.GroupID == groupID {
			var e groupEvent
			if err := json.Unmarshal([]byte(f.Message.Body), &e); err != nil {
				t.Fatal(err)
			}
			if e.Event == want {
				return e
			}
		}
	}
}

func TestGroups(t *testing.T) {
	base := testServer(t)
	suffix := fmt.Sprintf("%07d", time.Now().UnixNano()%10_000_000)
	ana := signUp(t, base, "+1720"+suffix, "Ana")
	ben := signUp(t, base, "+1730"+suffix, "Ben")
	cy := signUp(t, base, "+1740"+suffix, "Cy")
	bws := dial(t, base, ben.token)
	cws := dial(t, base, cy.token)

	// Ana creates a group with Ben; she is its admin.
	var g groupResp
	if code := post(t, base+"/v1/groups", ana.token, map[string]any{"name": " Hiking ", "members": []string{ben.id, ana.id}}, &g); code != 200 {
		t.Fatalf("create: status %d", code)
	}
	if g.Name != "Hiking" || len(g.Members) != 2 || g.role(ana.id) != "admin" || g.role(ben.id) != "member" {
		t.Fatalf("created group %+v", g)
	}
	if e := readEvent(t, bws, g.ID, "created"); e.Event != "created" || e.Actor != ana.id || e.Name != "Hiking" {
		t.Fatalf("created event %+v", e)
	}

	// Ben is not an admin, so he cannot add people; Ana can.
	if code := post(t, base+"/v1/groups/"+g.ID+"/members", ben.token, map[string]any{"user_ids": []string{cy.id}}, nil); code != http.StatusForbidden {
		t.Fatalf("member added someone: status %d", code)
	}
	if code := post(t, base+"/v1/groups/"+g.ID+"/members", ana.token, map[string]any{"user_ids": []string{cy.id}}, &g); code != 200 || len(g.Members) != 3 {
		t.Fatalf("add: status %d, %+v", code, g)
	}
	if e := readEvent(t, cws, g.ID, "added"); e.Event != "added" || len(e.Targets) != 1 || e.Targets[0] != cy.id {
		t.Fatalf("added event %+v", e)
	}

	// Outsiders cannot see the group.
	outsider := signUp(t, base, "+1750"+suffix, "Out")
	if code := get(t, base+"/v1/groups/"+g.ID, outsider.token, nil); code != http.StatusNotFound {
		t.Fatalf("outsider sees group: status %d", code)
	}

	// Settings and renames; only admins see the invite code.
	if code := do(t, "PATCH", base+"/v1/groups/"+g.ID, ben.token, map[string]any{"only_admins_send": true}, nil); code != http.StatusForbidden {
		t.Fatalf("member changed settings: status %d", code)
	}
	if code := do(t, "PATCH", base+"/v1/groups/"+g.ID, ben.token, map[string]any{"name": "Trail crew"}, &g); code != 200 || g.Name != "Trail crew" {
		t.Fatalf("rename: status %d, %+v", code, g)
	}
	if e := readEvent(t, cws, g.ID, "renamed"); e.Event != "renamed" || e.Name != "Trail crew" || e.Actor != ben.id {
		t.Fatalf("renamed event %+v", e)
	}
	if code := post(t, base+"/v1/groups/"+g.ID+"/invite", ana.token, nil, &g); code != 200 || g.InviteCode == nil {
		t.Fatalf("invite: status %d, %+v", code, g)
	}
	code := *g.InviteCode
	var benView groupResp
	get(t, base+"/v1/groups/"+g.ID, ben.token, &benView)
	if benView.InviteCode != nil {
		t.Fatal("member sees the invite code")
	}

	// The outsider previews and joins through the link.
	var preview struct {
		Name        string
		MemberCount int  `json:"member_count"`
		IsMember    bool `json:"is_member"`
	}
	if c := get(t, base+"/v1/invites/"+code, outsider.token, &preview); c != 200 || preview.Name != "Trail crew" || preview.MemberCount != 3 || preview.IsMember {
		t.Fatalf("preview: status %d, %+v", c, preview)
	}
	if c := post(t, base+"/v1/invites/"+code+"/join", outsider.token, nil, &g); c != 200 || len(g.Members) != 4 {
		t.Fatalf("join: status %d, %+v", c, g)
	}
	if e := readEvent(t, bws, g.ID, "joined"); e.Event != "joined" || e.Actor != outsider.id {
		t.Fatalf("joined event %+v", e)
	}
	resp, err := http.Get(base + "/join/" + code)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("invite page: %v", err)
	}
	page, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !strings.Contains(string(page), "novachat://join/"+code) || strings.Contains(string(page), "Trail crew") {
		t.Fatal("invite page should link into the app without naming the group")
	}

	// Resetting the link invalidates the old one.
	post(t, base+"/v1/groups/"+g.ID+"/invite", ana.token, nil, &g)
	if c := get(t, base+"/v1/invites/"+code, outsider.token, nil); c != http.StatusNotFound {
		t.Fatalf("old invite still works: status %d", c)
	}

	// Ana removes Cy, who hears about it and loses access.
	if c := do(t, "DELETE", base+"/v1/groups/"+g.ID+"/members/"+cy.id, ana.token, nil, &g); c != 200 || g.role(cy.id) != "" {
		t.Fatalf("remove: status %d", c)
	}
	if e := readEvent(t, cws, g.ID, "removed"); e.Event != "removed" || e.Targets[0] != cy.id {
		t.Fatalf("removed event %+v", e)
	}
	if c := get(t, base+"/v1/groups/"+g.ID, cy.token, nil); c != http.StatusNotFound {
		t.Fatalf("removed member still sees group: status %d", c)
	}

	// When the only admin leaves, the longest-standing member takes over.
	if c := do(t, "DELETE", base+"/v1/groups/"+g.ID+"/members/"+ana.id, ana.token, nil, nil); c != http.StatusNoContent {
		t.Fatalf("leave: status %d", c)
	}
	if e := readEvent(t, bws, g.ID, "left"); e.Event != "left" || e.Actor != ana.id {
		t.Fatalf("left event %+v", e)
	}
	if e := readEvent(t, bws, g.ID, "promoted"); e.Event != "promoted" || e.Targets[0] != ben.id {
		t.Fatalf("promoted event %+v", e)
	}
	var list []groupResp
	get(t, base+"/v1/groups", ben.token, &list)
	if len(list) != 1 || list[0].role(ben.id) != "admin" {
		t.Fatalf("Ben's groups %+v", list)
	}

	// Group photo, then the events are in Ben's sync but never twice in Ana's.
	jpeg := append([]byte{0xFF, 0xD8, 0xFF}, bytes.Repeat([]byte{2}, 500)...)
	req, _ := http.NewRequest("PUT", base+"/v1/groups/"+g.ID+"/avatar", bytes.NewReader(jpeg))
	req.Header.Set("Authorization", "Bearer "+ben.token)
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 200 {
		t.Fatalf("group photo: %v %v", err, resp.Status)
	}
	var synced []struct {
		RecipientID string  `json:"recipient_id"`
		GroupID     *string `json:"group_id"`
	}
	get(t, base+"/v1/sync?after=0", ana.token, &synced)
	for _, m := range synced {
		if m.GroupID != nil && m.RecipientID != ana.id {
			t.Fatalf("Ana's sync has someone else's copy of an event: %+v", m)
		}
	}
}
