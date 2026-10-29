package main

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"

	"github.com/mahmudultopu/novachat/server/internal/config"
	"github.com/mahmudultopu/novachat/server/internal/db"
	"github.com/mahmudultopu/novachat/server/internal/media"
)

// TestEndToEnd signs up two users and sends a message between them over
// WebSockets. It needs a scratch Postgres database and Redis:
//
//	NOVACHAT_TEST_DATABASE_URL=postgres://novachat:novachat@localhost:5432/novachat_test?sslmode=disable \
//	NOVACHAT_TEST_REDIS_URL=redis://localhost:6379/15 go test ./...
func TestEndToEnd(t *testing.T) {
	dbURL, redisURL := os.Getenv("NOVACHAT_TEST_DATABASE_URL"), os.Getenv("NOVACHAT_TEST_REDIS_URL")
	if dbURL == "" || redisURL == "" {
		t.Skip("set NOVACHAT_TEST_DATABASE_URL and NOVACHAT_TEST_REDIS_URL to run")
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := db.Open(ctx, dbURL)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	ropts, _ := redis.ParseURL(redisURL)
	rdb := redis.NewClient(ropts)
	defer rdb.Close()
	rdb.FlushDB(ctx)

	cfg := config.Config{Env: "dev", DevOTP: "123456"}
	handler, closeHub := NewHandler(ctx, cfg, pool, rdb)
	defer closeHub()
	srv := httptest.NewServer(handler)
	defer srv.Close()

	suffix := fmt.Sprintf("%08d", time.Now().UnixNano()%100_000_000)
	alice := signUp(t, srv.URL, "+1555"+suffix, "Alice")
	bob := signUp(t, srv.URL, "+1666"+suffix, "Bob")

	// A wrong code is rejected.
	var errResp map[string]string
	if code := post(t, srv.URL+"/v1/auth/verify", "", map[string]string{"phone": "+1555" + suffix, "code": "000000"}, &errResp); code != http.StatusUnauthorized {
		t.Fatalf("wrong code: got status %d", code)
	}

	// Alice finds Bob by phone number.
	var found struct{ ID string }
	if code := get(t, srv.URL+"/v1/users/by-phone?phone=%2B1666"+suffix, alice.token, &found); code != 200 || found.ID != bob.id {
		t.Fatalf("lookup Bob: status %d, id %q", code, found.ID)
	}

	aws := dial(t, srv.URL, alice.token)
	bws := dial(t, srv.URL, bob.token)

	clientID := "0b6a9f3e-7c1d-4d5e-9f00-1234567890ab"
	send := map[string]string{"type": "send", "client_id": clientID, "to": bob.id, "body": "  Hello Bob  "}
	if err := aws.WriteJSON(send); err != nil {
		t.Fatal(err)
	}

	sent := readType(t, aws, "message")
	got := readType(t, bws, "message")
	if sent.Message.ClientID != clientID || got.Message.ID != sent.Message.ID || got.Message.Body != "Hello Bob" {
		t.Fatalf("unexpected messages: sent %+v got %+v", sent.Message, got.Message)
	}

	// Resending the same client_id does not create a second message.
	aws.WriteJSON(send)
	again := readType(t, aws, "message")
	if again.Message.ID != sent.Message.ID {
		t.Fatalf("retry created a new message: %d != %d", again.Message.ID, sent.Message.ID)
	}

	// Bob reads it and Alice sees the blue ticks.
	bws.WriteJSON(map[string]any{"type": "receipt", "status": "read", "message_ids": []int64{got.Message.ID}})
	receipt := readType(t, aws, "receipt")
	if receipt.Status != "read" || len(receipt.MessageIDs) != 1 || receipt.MessageIDs[0] != sent.Message.ID {
		t.Fatalf("unexpected receipt %+v", receipt)
	}

	// Alice sees Bob online, and offline with a last-seen time once he leaves.
	aws.WriteJSON(map[string]string{"type": "presence_query", "to": bob.id})
	if p := readType(t, aws, "presence"); p.Online == nil || !*p.Online || p.UserID != bob.id {
		t.Fatalf("presence query %+v", p)
	}
	bws2 := dial(t, srv.URL, bob.token)
	bws.Close()
	bws = bws2
	time.Sleep(100 * time.Millisecond)
	bws2.Close()
	if p := readType(t, aws, "presence"); p.Online == nil || *p.Online || p.LastSeenAt == nil {
		t.Fatalf("offline presence %+v", p)
	}
	bws = dial(t, srv.URL, bob.token)

	// Profile photo: upload, fetch, and it shows on the profile.
	jpeg := append([]byte{0xFF, 0xD8, 0xFF}, bytes.Repeat([]byte{1}, 1000)...)
	req, _ := http.NewRequest("PUT", srv.URL+"/v1/me/avatar", bytes.NewReader(jpeg))
	req.Header.Set("Authorization", "Bearer "+bob.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("set avatar: %v %v", err, resp.Status)
	}
	resp.Body.Close()
	var prof struct {
		AvatarID string `json:"avatar_id"`
	}
	get(t, srv.URL+"/v1/users/"+bob.id, alice.token, &prof)
	req, _ = http.NewRequest("GET", srv.URL+"/v1/avatars/"+prof.AvatarID, nil)
	req.Header.Set("Authorization", "Bearer "+alice.token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 || resp.Header.Get("Content-Type") != "image/jpeg" {
		t.Fatalf("get avatar: %v %v", err, resp.Status)
	}
	resp.Body.Close()

	// Bob's other device catches up with sync, and both see the chat listed.
	var synced []struct{ ID int64 }
	get(t, fmt.Sprintf("%s/v1/sync?after=%d", srv.URL, sent.Message.ID-1), bob.token, &synced)
	if len(synced) != 1 || synced[0].ID != sent.Message.ID {
		t.Fatalf("sync returned %+v", synced)
	}
	var convs []struct {
		Peer        struct{ ID, Name string }
		LastMessage struct{ Body string } `json:"last_message"`
		Unread      int
	}
	get(t, srv.URL+"/v1/conversations", bob.token, &convs)
	if len(convs) != 1 || convs[0].Peer.Name != "Alice" || convs[0].LastMessage.Body != "Hello Bob" || convs[0].Unread != 0 {
		t.Fatalf("conversations %+v", convs)
	}

	// End-to-end encryption: Bob publishes keys, Alice claims a bundle and
	// sends an envelope the server stores but cannot read.
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	identity, spk := randomKey(), randomKey()
	keysReq := map[string]any{
		"identity_key":  identity,
		"signing_key":   []byte(pub),
		"signed_prekey": map[string]any{"id": 1, "public_key": spk, "signature": ed25519.Sign(priv, spk)},
		"one_time_prekeys": []map[string]any{
			{"id": 7, "public_key": randomKey()},
			{"id": 8, "public_key": randomKey()},
		},
	}
	var left struct {
		OneTimePreKeys int `json:"one_time_prekeys"`
	}
	if code := do(t, "PUT", srv.URL+"/v1/keys", bob.token, keysReq, &left); code != 200 || left.OneTimePreKeys != 2 {
		t.Fatalf("publish keys: status %d, left %d", code, left.OneTimePreKeys)
	}
	badSig := map[string]any{"identity_key": identity, "signing_key": []byte(pub),
		"signed_prekey": map[string]any{"id": 1, "public_key": spk, "signature": make([]byte, 64)}}
	if code := do(t, "PUT", srv.URL+"/v1/keys", bob.token, badSig, nil); code != http.StatusBadRequest {
		t.Fatalf("bad signature accepted: status %d", code)
	}
	var bundle struct {
		IdentityKey   []byte `json:"identity_key"`
		OneTimePreKey *struct {
			ID int `json:"id"`
		} `json:"one_time_prekey"`
	}
	for _, want := range []int{7, 8, 0} {
		bundle.OneTimePreKey = nil
		if code := get(t, srv.URL+"/v1/keys/"+bob.id, alice.token, &bundle); code != 200 || !bytes.Equal(bundle.IdentityKey, identity) {
			t.Fatalf("claim bundle: status %d", code)
		}
		got := 0
		if bundle.OneTimePreKey != nil {
			got = bundle.OneTimePreKey.ID
		}
		if got != want {
			t.Fatalf("claimed one-time prekey %d, want %d", got, want)
		}
	}
	if code := get(t, srv.URL+"/v1/keys/"+alice.id, bob.token, nil); code != http.StatusNotFound {
		t.Fatalf("bundle for user without keys: status %d", code)
	}

	encSend := map[string]string{"type": "send", "client_id": "1b6a9f3e-7c1d-4d5e-9f00-1234567890ab", "to": bob.id,
		"envelope": `{"v":1,"ct":"opaque"}`, "identity_key": base64.StdEncoding.EncodeToString(randomKey())}
	aws.WriteJSON(encSend)
	if f := readType(t, aws, "error"); f.Code != "stale_identity" {
		t.Fatalf("stale identity not caught: %+v", f)
	}
	encSend["identity_key"] = base64.StdEncoding.EncodeToString(identity)
	aws.WriteJSON(encSend)
	enc := readType(t, bws, "message")
	if enc.Message.Envelope == nil || *enc.Message.Envelope != encSend["envelope"] || enc.Message.Body != "" {
		t.Fatalf("encrypted message %+v", enc.Message)
	}

	// Bob moves to a new phone: its new identity key signs the old phone out.
	var codeResp struct {
		DevCode string `json:"dev_code"`
	}
	post(t, srv.URL+"/v1/auth/request-code", "", map[string]string{"phone": "+1666" + suffix}, &codeResp)
	var newPhone struct{ Token string }
	post(t, srv.URL+"/v1/auth/verify", "", map[string]string{"phone": "+1666" + suffix, "code": codeResp.DevCode}, &newPhone)
	pub2, priv2, _ := ed25519.GenerateKey(rand.Reader)
	spk2 := randomKey()
	takeover := map[string]any{"identity_key": randomKey(), "signing_key": []byte(pub2),
		"signed_prekey": map[string]any{"id": 1, "public_key": spk2, "signature": ed25519.Sign(priv2, spk2)}}
	if code := do(t, "PUT", srv.URL+"/v1/keys", newPhone.Token, takeover, nil); code != 200 {
		t.Fatalf("publish from new phone: status %d", code)
	}
	if code := get(t, srv.URL+"/v1/me", bob.token, nil); code != http.StatusUnauthorized {
		t.Fatalf("old phone still signed in: status %d", code)
	}
	if code := get(t, srv.URL+"/v1/me", newPhone.Token, nil); code != 200 {
		t.Fatalf("new phone signed out: status %d", code)
	}

	// Attachments: an encrypted blob goes up and comes back byte for byte.
	blob := bytes.Repeat([]byte{0xAB, 0x01}, 100_000)
	req, _ = http.NewRequest("POST", srv.URL+"/v1/attachments", bytes.NewReader(blob))
	req.Header.Set("Authorization", "Bearer "+alice.token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != http.StatusCreated {
		t.Fatalf("upload: %v %v", err, resp.Status)
	}
	var up struct{ ID string }
	json.NewDecoder(resp.Body).Decode(&up)
	resp.Body.Close()
	req, _ = http.NewRequest("GET", srv.URL+"/v1/attachments/"+up.ID, nil)
	req.Header.Set("Authorization", "Bearer "+newPhone.Token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("download: %v %v", err, resp.Status)
	}
	got2, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if !bytes.Equal(got2, blob) {
		t.Fatalf("downloaded %d bytes, want %d", len(got2), len(blob))
	}
	req, _ = http.NewRequest("POST", srv.URL+"/v1/attachments", bytes.NewReader(make([]byte, media.MaxSize+1)))
	req.Header.Set("Authorization", "Bearer "+alice.token)
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized upload accepted: %v", err)
	}

	// Without a token the socket is refused.
	if _, resp, err := websocket.DefaultDialer.Dial(wsURL(srv.URL), nil); err == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated dial: err %v", err)
	}
}

type account struct{ id, token string }

func signUp(t *testing.T, base, phone, name string) account {
	t.Helper()
	var req struct {
		DevCode string `json:"dev_code"`
	}
	if code := post(t, base+"/v1/auth/request-code", "", map[string]string{"phone": phone}, &req); code != 200 || req.DevCode == "" {
		t.Fatalf("request-code: status %d, dev code %q", code, req.DevCode)
	}
	var ver struct {
		Token string
		User  struct{ ID string }
		IsNew bool `json:"is_new"`
	}
	if code := post(t, base+"/v1/auth/verify", "", map[string]string{"phone": phone, "code": req.DevCode, "device_name": "test"}, &ver); code != 200 || !ver.IsNew {
		t.Fatalf("verify: status %d, %+v", code, ver)
	}
	var me struct{ Name string }
	if code := do(t, "PATCH", base+"/v1/me", ver.Token, map[string]string{"name": name}, &me); code != 200 || me.Name != name {
		t.Fatalf("set name: status %d", code)
	}
	return account{ver.User.ID, ver.Token}
}

type frame struct {
	Type       string
	Code       string
	UserID     string     `json:"user_id"`
	Online     *bool      `json:"online"`
	LastSeenAt *time.Time `json:"last_seen_at"`
	Status     string
	MessageIDs []int64 `json:"message_ids"`
	Message    *struct {
		ID       int64
		ClientID string `json:"client_id"`
		Body     string
		Envelope *string
	}
}

func readType(t *testing.T, c *websocket.Conn, typ string) frame {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	for {
		var f frame
		if err := c.ReadJSON(&f); err != nil {
			t.Fatalf("waiting for %s: %v", typ, err)
		}
		if f.Type == typ {
			return f
		}
	}
}

func randomKey() []byte {
	k := make([]byte, 32)
	rand.Read(k)
	return k
}

func wsURL(base string) string { return "ws" + strings.TrimPrefix(base, "http") + "/v1/ws" }

func dial(t *testing.T, base, token string) *websocket.Conn {
	t.Helper()
	c, _, err := websocket.DefaultDialer.Dial(wsURL(base), http.Header{"Authorization": {"Bearer " + token}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	readType(t, c, "ready")
	return c
}

func post(t *testing.T, url, token string, body, out any) int {
	return do(t, "POST", url, token, body, out)
}
func get(t *testing.T, url, token string, out any) int { return do(t, "GET", url, token, nil, out) }

func do(t *testing.T, method, url, token string, body, out any) int {
	t.Helper()
	var rd *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	req, _ := http.NewRequest(method, url, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}
