package main

import (
	"fmt"
	"testing"
	"time"
)

func TestPrivacyAndBlocking(t *testing.T) {
	base := testServer(t)
	n := time.Now().UnixNano() % 10_000_000
	ana := signUp(t, base, fmt.Sprintf("+1760%07d", n), "Ana")
	ben := signUp(t, base, fmt.Sprintf("+1761%07d", n), "Ben")
	if code := do(t, "PATCH", base+"/v1/me", ana.token, map[string]string{"about": "Busy"}, nil); code != 200 {
		t.Fatalf("set about: %d", code)
	}

	var p struct {
		LastSeen     string `json:"last_seen"`
		About        string
		ReadReceipts bool `json:"read_receipts"`
	}
	if code := get(t, base+"/v1/me/privacy", ana.token, &p); code != 200 || p.LastSeen != "everyone" || !p.ReadReceipts {
		t.Fatalf("default privacy: %d %+v", code, p)
	}
	if code := do(t, "PATCH", base+"/v1/me/privacy", ana.token, map[string]string{"about": "friends"}, nil); code != 400 {
		t.Fatalf("bad level accepted: %d", code)
	}
	if code := do(t, "PATCH", base+"/v1/me/privacy", ana.token, map[string]any{"about": "chats", "last_seen": "nobody", "read_receipts": false}, &p); code != 200 ||
		p.About != "chats" || p.LastSeen != "nobody" || p.ReadReceipts {
		t.Fatalf("set privacy: %d %+v", code, p)
	}

	about := func() string {
		t.Helper()
		var u struct{ About string }
		if code := get(t, base+"/v1/users/"+ana.id, ben.token, &u); code != 200 {
			t.Fatalf("get user: %d", code)
		}
		return u.About
	}
	if got := about(); got != "" {
		t.Fatalf("about shown to someone Ana never messaged: %q", got)
	}
	var self struct{ About string }
	if get(t, base+"/v1/users/"+ana.id, ana.token, &self); self.About != "Busy" {
		t.Fatalf("own about hidden from self: %q", self.About)
	}

	// Once Ana messages Ben, "chats" includes him.
	aws := dial(t, base, ana.token)
	aws.WriteJSON(map[string]string{"type": "send", "client_id": "2c6a9f3e-7c1d-4d5e-9f00-1234567890ab", "to": ben.id, "body": "hi"})
	readType(t, aws, "message")
	if got := about(); got != "Busy" {
		t.Fatalf("about hidden from a chat: %q", got)
	}

	// Last seen "nobody" hides being online, though Ana is connected.
	bws := dial(t, base, ben.token)
	bws.WriteJSON(map[string]string{"type": "presence_query", "to": ana.id})
	if f := readType(t, bws, "presence"); f.UserID != ana.id || f.Online == nil || *f.Online || f.LastSeenAt != nil {
		t.Fatalf("presence leaked: %+v", f)
	}

	// Blocking hides the profile from the blocked person, and unblocking shows it again.
	if code := do(t, "PUT", base+"/v1/blocks/"+ben.id, ana.token, nil, nil); code != 204 {
		t.Fatalf("block: %d", code)
	}
	if got := about(); got != "" {
		t.Fatalf("about shown to a blocked user: %q", got)
	}
	var blocked []struct{ ID string }
	if code := get(t, base+"/v1/blocks", ana.token, &blocked); code != 200 || len(blocked) != 1 || blocked[0].ID != ben.id {
		t.Fatalf("block list: %d %+v", code, blocked)
	}
	if code := do(t, "DELETE", base+"/v1/blocks/"+ben.id, ana.token, nil, nil); code != 204 {
		t.Fatalf("unblock: %d", code)
	}
	if got := about(); got != "Busy" {
		t.Fatalf("about still hidden after unblock: %q", got)
	}
	if code := do(t, "PUT", base+"/v1/blocks/"+ana.id, ana.token, nil, nil); code != 400 {
		t.Fatalf("blocking yourself: %d", code)
	}

	// Report and block in one go.
	if code := post(t, base+"/v1/reports", ana.token, map[string]any{"user_id": ben.id, "reason": "Spam", "block": true}, nil); code != 204 {
		t.Fatalf("report: %d", code)
	}
	if get(t, base+"/v1/blocks", ana.token, &blocked); len(blocked) != 1 {
		t.Fatalf("report did not block: %+v", blocked)
	}
	if code := post(t, base+"/v1/reports", ana.token, map[string]any{"user_id": ben.id, "reason": ""}, nil); code != 400 {
		t.Fatalf("empty reason accepted: %d", code)
	}
}

func TestContactMatching(t *testing.T) {
	base := testServer(t)
	n := time.Now().UnixNano() % 10_000_000
	ana := signUp(t, base, fmt.Sprintf("+1762%07d", n), "Ana")
	ben := signUp(t, base, fmt.Sprintf("+1763%07d", n), "Ben")

	var res struct {
		Users []struct{ ID, Name string }
	}
	phones := []string{
		fmt.Sprintf("+1 763 %07d", n), // Ben, with spaces
		fmt.Sprintf("+1763%07d", n),   // Ben again
		fmt.Sprintf("+1762%07d", n),   // Ana herself
		"+19995550000",                // nobody
		"0171 not a number",
	}
	if code := post(t, base+"/v1/contacts/match", ana.token, map[string]any{"phones": phones}, &res); code != 200 {
		t.Fatalf("match: %d", code)
	}
	if len(res.Users) != 1 || res.Users[0].ID != ben.id || res.Users[0].Name != "Ben" {
		t.Fatalf("matched %+v", res.Users)
	}
	if code := post(t, base+"/v1/contacts/match", ana.token, map[string]any{"phones": make([]string, 5001)}, nil); code != 400 {
		t.Fatalf("huge address book accepted: %d", code)
	}
}
