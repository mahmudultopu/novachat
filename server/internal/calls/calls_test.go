package calls

import (
	"context"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestMintTURNCredential(t *testing.T) {
	exp := time.Unix(1_900_000_000, 0)
	user, pass := MintTURNCredential("s3cret", "u1", exp)
	if user != "1900000000:u1" {
		t.Fatalf("username = %q", user)
	}
	mac := hmac.New(sha1.New, []byte("s3cret"))
	mac.Write([]byte(user))
	if want := base64.StdEncoding.EncodeToString(mac.Sum(nil)); pass != want {
		t.Fatalf("password = %q, want %q", pass, want)
	}
}

func TestServers(t *testing.T) {
	cf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer tok" || !strings.Contains(r.URL.Path, "/keys/kid/") {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		w.Write([]byte(`{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]},{"urls":"turn:turn.cloudflare.com:3478","username":"cu","credential":"cp"}]}`))
	}))
	defer cf.Close()

	h := &Handlers{
		Config: Config{
			STUNURLs:        []string{"stun:stun.l.google.com:19302"},
			TURNURLs:        []string{"turn:turn.example.com:3478"},
			TURNSecret:      "s",
			CloudflareKeyID: "kid",
			CloudflareToken: "tok",
		},
		cloudflareURL: cf.URL,
	}
	got := h.Servers(context.Background(), "u1", time.Now())
	if len(got) != 4 {
		t.Fatalf("got %d servers: %+v", len(got), got)
	}
	if got[1].Username == "" || got[1].Credential == "" {
		t.Fatalf("coturn server has no credentials: %+v", got[1])
	}
	if got[3].URLs[0] != "turn:turn.cloudflare.com:3478" || got[3].Username != "cu" {
		t.Fatalf("cloudflare server = %+v", got[3])
	}

	// A failing TURN service is skipped, not fatal.
	h.Config.CloudflareToken = "wrong"
	if got := h.Servers(context.Background(), "u1", time.Now()); len(got) != 2 {
		t.Fatalf("got %d servers with a failing Cloudflare: %+v", len(got), got)
	}
}

func TestParseOneServer(t *testing.T) {
	got, err := parseICEServers([]byte(`{"urls":["turn:a"],"username":"u","credential":"p"}`))
	if err != nil || len(got) != 1 || got[0].URLs[0] != "turn:a" {
		t.Fatalf("got %+v, %v", got, err)
	}
}
