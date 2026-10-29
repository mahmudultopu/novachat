// Package calls hands phones the STUN and TURN servers they need to set up
// voice and video calls. Calls are peer to peer (WebRTC) and their offers
// and answers travel as end-to-end encrypted messages, so the server never
// sees a call's content or its keys. A TURN server only relays packets that
// are already encrypted, for phones that can't reach each other directly.
package calls

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/httpx"
)

// credentialTTL is how long minted TURN credentials stay valid.
const credentialTTL = 12 * time.Hour

// ICEServer is one entry of WebRTC's iceServers list.
type ICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

// Config says where the STUN and TURN servers are. Any TURN option is
// optional: without one, calls still work when the phones can reach each
// other directly, which fails on some mobile networks.
type Config struct {
	STUNURLs []string
	// TURNURLs with TURNSecret: a coturn server using static-auth-secret.
	// Credentials are minted per request (the TURN REST API scheme).
	TURNURLs   []string
	TURNSecret string
	// CloudflareKeyID and CloudflareToken: Cloudflare's managed TURN service.
	CloudflareKeyID string
	CloudflareToken string
}

type Handlers struct {
	Config Config
	Auth   *auth.Handlers
	// HTTP is used to ask Cloudflare for credentials; nil means a default client.
	HTTP *http.Client
	// cloudflareURL is overridden in tests.
	cloudflareURL string
}

func (h *Handlers) Register(mux *http.ServeMux) {
	mux.Handle("GET /v1/calls/ice", h.Auth.Require(http.HandlerFunc(h.ice)))
}

func (h *Handlers) ice(w http.ResponseWriter, r *http.Request) {
	servers := h.Servers(r.Context(), auth.UserID(r.Context()), time.Now())
	httpx.JSON(w, http.StatusOK, map[string]any{"ice_servers": servers})
}

// Servers lists the ICE servers for one user. A TURN service that fails is
// left out (and logged) so calls can still try a direct connection.
func (h *Handlers) Servers(ctx context.Context, userID string, now time.Time) []ICEServer {
	out := []ICEServer{}
	if len(h.Config.STUNURLs) > 0 {
		out = append(out, ICEServer{URLs: h.Config.STUNURLs})
	}
	if len(h.Config.TURNURLs) > 0 && h.Config.TURNSecret != "" {
		user, pass := MintTURNCredential(h.Config.TURNSecret, userID, now.Add(credentialTTL))
		out = append(out, ICEServer{URLs: h.Config.TURNURLs, Username: user, Credential: pass})
	}
	if h.Config.CloudflareKeyID != "" && h.Config.CloudflareToken != "" {
		cf, err := h.cloudflare(ctx)
		if err != nil {
			slog.Error("cloudflare turn credentials", "err", err)
		} else {
			out = append(out, cf...)
		}
	}
	return out
}

// MintTURNCredential makes a time-limited username and password that a
// coturn server with the same static-auth-secret accepts.
func MintTURNCredential(secret, userID string, expires time.Time) (username, password string) {
	username = strconv.FormatInt(expires.Unix(), 10) + ":" + userID
	mac := hmac.New(sha1.New, []byte(secret))
	mac.Write([]byte(username))
	return username, base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

func (h *Handlers) cloudflare(ctx context.Context) ([]ICEServer, error) {
	base := h.cloudflareURL
	if base == "" {
		base = "https://rtc.live.cloudflare.com"
	}
	url := fmt.Sprintf("%s/v1/turn/keys/%s/credentials/generate-ice-servers", base, h.Config.CloudflareKeyID)
	body, _ := json.Marshal(map[string]int{"ttl": int(credentialTTL.Seconds())})
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+h.Config.CloudflareToken)
	req.Header.Set("Content-Type", "application/json")
	client := h.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("cloudflare answered %s", resp.Status)
	}
	var parsed struct {
		ICEServers json.RawMessage `json:"iceServers"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&parsed); err != nil {
		return nil, err
	}
	return parseICEServers(parsed.ICEServers)
}

// parseICEServers accepts one server or a list, with "urls" as a string or
// a list, the shapes WebRTC itself allows.
func parseICEServers(raw json.RawMessage) ([]ICEServer, error) {
	type wire struct {
		URLs       json.RawMessage `json:"urls"`
		Username   string          `json:"username"`
		Credential string          `json:"credential"`
	}
	var list []wire
	if err := json.Unmarshal(raw, &list); err != nil {
		var one wire
		if err := json.Unmarshal(raw, &one); err != nil {
			return nil, fmt.Errorf("unexpected iceServers: %w", err)
		}
		list = []wire{one}
	}
	out := make([]ICEServer, 0, len(list))
	for _, s := range list {
		var urls []string
		if err := json.Unmarshal(s.URLs, &urls); err != nil {
			var u string
			if err := json.Unmarshal(s.URLs, &u); err != nil {
				return nil, fmt.Errorf("unexpected urls: %w", err)
			}
			urls = []string{u}
		}
		out = append(out, ICEServer{URLs: urls, Username: s.Username, Credential: s.Credential})
	}
	return out, nil
}
