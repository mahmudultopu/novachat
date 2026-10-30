package chat

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/gorilla/websocket"

	"github.com/mahmudultopu/novachat/server/internal/auth"
	"github.com/mahmudultopu/novachat/server/internal/keys"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingInterval   = 25 * time.Second
	maxFrameBytes  = 256 << 10
	maxBodyRunes   = 4096
	maxEnvelope    = 192 << 10
	sendBufferSize = 256
)

// Frames sent by the app.
type inbound struct {
	Type     string `json:"type"` // "send", "receipt", "typing", "presence_query" or "active"
	ClientID string `json:"client_id,omitempty"`
	To       string `json:"to,omitempty"`
	Body     string `json:"body,omitempty"`
	// Encrypted messages: one envelope per device, for every device of the
	// recipient and (optionally) the sender's other devices.
	Envelopes  map[string]deviceEnvelope `json:"envelopes,omitempty"`
	Status     string                    `json:"status,omitempty"` // "delivered" or "read"
	MessageIDs []int64                   `json:"message_ids,omitempty"`
	// "active" frames: whether the app is on screen. A phone that stays
	// connected in the background (for notifications) is not "online".
	Active *bool `json:"active,omitempty"`
}

// deviceEnvelope is a message encrypted for one device, with the identity key
// it was encrypted to (base64), so a stale session is caught.
type deviceEnvelope struct {
	Envelope    string `json:"envelope"`
	IdentityKey string `json:"identity_key"`
}

// Frames sent by the server.
type outbound struct {
	Type       string   `json:"type"` // "ready", "message", "receipt", "typing", "presence" or "error"
	UserID     string   `json:"user_id,omitempty"`
	DeviceID   string   `json:"device_id,omitempty"` // "ready": which device this connection is
	Message    *Message `json:"message,omitempty"`
	Status     string   `json:"status,omitempty"`
	MessageIDs []int64  `json:"message_ids,omitempty"`
	At         string   `json:"at,omitempty"`
	From       string   `json:"from,omitempty"`
	ClientID   string   `json:"client_id,omitempty"`
	Code       string   `json:"code,omitempty"`
	Error      string   `json:"error,omitempty"`
	// Presence frames: is the user online, and when were they last seen.
	Online     *bool      `json:"online,omitempty"`
	LastSeenAt *time.Time `json:"last_seen_at,omitempty"`
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	// The app authenticates with a bearer token, not cookies, so a
	// cross-origin page cannot ride on a user's session.
	CheckOrigin: func(*http.Request) bool { return true },
}

type client struct {
	userID   string
	deviceID string
	conn     *websocket.Conn
	send     chan []byte
	cancel   context.CancelFunc
	// active is false while the app is in the background.
	active atomic.Bool
}

// enqueue queues a frame without blocking. A phone that cannot keep up is
// disconnected; it catches up through /v1/sync when it reconnects.
func (c *client) enqueue(b []byte) {
	select {
	case c.send <- b:
	default:
		c.cancel()
	}
}

func (c *client) sendJSON(v outbound) {
	b, _ := json.Marshal(v)
	c.enqueue(b)
}

func (h *Handlers) serveWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return // Upgrade already wrote the HTTP error
	}
	ctx, cancel := context.WithCancel(context.Background())
	d := auth.CurrentDevice(r.Context())
	c := &client{userID: d.UserID, deviceID: d.ID, conn: conn, send: make(chan []byte, sendBufferSize), cancel: cancel}
	// Apps count as on screen until they say otherwise (older apps never do).
	c.active.Store(true)
	wasOnline := h.Hub.anyActive(c.userID)
	if err := h.Hub.add(ctx, c); err != nil {
		slog.Error("hub add failed", "err", err)
		cancel()
		conn.Close()
		return
	}
	defer func() {
		h.Hub.remove(c)
		if c.active.Load() && !h.Hub.anyActive(c.userID) {
			h.wentOffline(c.userID)
		}
	}()
	if !wasOnline {
		h.Hub.MarkOnline(ctx, c.userID)
		go h.announcePresence(c.userID, true, nil)
	}
	go h.keepOnline(ctx, c.userID)

	go c.writeLoop(ctx)
	c.sendJSON(outbound{Type: "ready", UserID: c.userID, DeviceID: c.deviceID})
	h.readLoop(ctx, c)
	cancel()
}

func (c *client) writeLoop(ctx context.Context) {
	ticker := time.NewTicker(pingInterval)
	defer func() {
		ticker.Stop()
		c.conn.Close()
	}()
	for {
		select {
		case <-ctx.Done():
			c.conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""), time.Now().Add(time.Second))
			return
		case b := <-c.send:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				c.cancel()
				return
			}
		case <-ticker.C:
			c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				c.cancel()
				return
			}
		}
	}
}

func (h *Handlers) readLoop(ctx context.Context, c *client) {
	c.conn.SetReadLimit(maxFrameBytes)
	c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})
	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		c.conn.SetReadDeadline(time.Now().Add(pongWait))
		var in inbound
		if err := json.Unmarshal(data, &in); err != nil {
			c.sendJSON(outbound{Type: "error", Code: "bad_frame", Error: "frame is not valid JSON"})
			continue
		}
		switch in.Type {
		case "send":
			h.handleSend(ctx, c, in)
		case "receipt":
			h.handleReceipt(ctx, c, in)
		case "presence_query":
			h.handlePresenceQuery(ctx, c, in.To)
		case "active":
			if in.Active != nil {
				h.setActive(ctx, c, *in.Active)
			}
		case "typing":
			if auth.IsUUID(in.To) && in.To != c.userID {
				// Someone who blocked you doesn't see you typing.
				if blocked, err := h.Auth.Store.HasBlocked(ctx, in.To, c.userID); err == nil && !blocked {
					b, _ := json.Marshal(outbound{Type: "typing", From: c.userID})
					h.Hub.Publish(ctx, in.To, b)
				}
			}
		default:
			c.sendJSON(outbound{Type: "error", Code: "unknown_type", Error: "unknown frame type"})
		}
	}
}

func (h *Handlers) handleSend(ctx context.Context, c *client, in inbound) {
	fail := func(code, msg string) {
		c.sendJSON(outbound{Type: "error", Code: code, Error: msg, ClientID: in.ClientID})
	}
	body := strings.TrimSpace(in.Body)
	var envelopes map[string]string
	switch {
	case !auth.IsUUID(in.ClientID):
		fail("bad_client_id", "client_id must be a UUID")
		return
	case !auth.IsUUID(in.To):
		fail("bad_recipient", "to must be a user id")
		return
	case len(in.Envelopes) > 0:
		size := 0
		for _, e := range in.Envelopes {
			size += len(e.Envelope)
		}
		if size > maxEnvelope {
			fail("too_long", "message is too long")
			return
		}
		ok, err := h.devicesMatch(ctx, c, in.To, in.Envelopes)
		if err != nil {
			slog.Error("device lookup failed", "err", err)
			fail("internal", "could not send, try again")
			return
		}
		if !ok {
			// Someone added, removed or reinstalled a device: the app
			// fetches the current devices and encrypts again.
			fail("devices_changed", "devices changed, encrypt again")
			return
		}
		body, envelopes = "", map[string]string{}
		for id, e := range in.Envelopes {
			envelopes[id] = e.Envelope
		}
	case to(in.To) == c.userID:
		fail("bad_recipient", "nothing to send to yourself")
		return
	case body == "":
		fail("empty", "message is empty")
		return
	case utf8.RuneCountInString(body) > maxBodyRunes:
		fail("too_long", "message is too long")
		return
	}
	m, inserted, err := h.Store.Insert(ctx, c.userID, c.deviceID, strings.ToLower(in.ClientID), to(in.To), body, envelopes)
	if errors.Is(err, ErrUnknownRecipient) {
		fail("bad_recipient", err.Error())
		return
	}
	if err != nil {
		slog.Error("insert message failed", "err", err)
		fail("internal", "could not send, try again")
		return
	}
	b, _ := json.Marshal(outbound{Type: "message", Message: &m})
	// The sender's own devices get the stored copy too: that is the "sent"
	// tick, matched to the pending bubble by client_id.
	h.Hub.Publish(ctx, c.userID, b)
	if inserted && m.RecipientID != c.userID {
		h.Hub.Publish(ctx, m.RecipientID, b)
	}
}

func (h *Handlers) keepOnline(ctx context.Context, userID string) {
	t := time.NewTicker(presenceRefresh)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if h.Hub.anyActive(userID) {
				h.Hub.MarkOnline(ctx, userID)
			}
		}
	}
}

// setActive records the app moving on or off screen, and updates the
// user's presence when that changes whether they are online.
func (h *Handlers) setActive(ctx context.Context, c *client, active bool) {
	wasOnline := h.Hub.anyActive(c.userID)
	if c.active.Swap(active) == active {
		return
	}
	switch {
	case active && !wasOnline:
		h.Hub.MarkOnline(ctx, c.userID)
		go h.announcePresence(c.userID, true, nil)
	case !active && !h.Hub.anyActive(c.userID):
		go h.wentOffline(c.userID)
	}
}

func (h *Handlers) wentOffline(userID string) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	h.Hub.MarkOffline(ctx, userID)
	if err := h.Auth.Store.TouchLastSeen(ctx, userID); err != nil {
		slog.Error("last seen update failed", "err", err)
	}
	now := time.Now().UTC()
	h.announcePresence(userID, false, &now)
}

// announcePresence tells everyone userID chats with that they came online
// or went offline.
func (h *Handlers) announcePresence(userID string, online bool, lastSeen *time.Time) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	peers, err := h.Store.PresenceAudience(ctx, userID, 200)
	if err != nil {
		slog.Error("presence peers failed", "err", err)
		return
	}
	b, _ := json.Marshal(outbound{Type: "presence", UserID: userID, Online: &online, LastSeenAt: lastSeen})
	for _, p := range peers {
		h.Hub.Publish(ctx, p, b)
	}
}

func (h *Handlers) handlePresenceQuery(ctx context.Context, c *client, userID string) {
	if !auth.IsUUID(userID) {
		return
	}
	// Hidden last seen hides being online too: answer "offline, no time".
	if ok, err := h.Auth.Store.CanSeeLastSeen(ctx, c.userID, userID); err != nil || !ok {
		off := false
		c.sendJSON(outbound{Type: "presence", UserID: userID, Online: &off})
		return
	}
	online := h.Hub.IsOnline(ctx, userID)
	f := outbound{Type: "presence", UserID: userID, Online: &online}
	if !online {
		if u, err := h.Auth.Store.UserByID(ctx, userID); err == nil {
			f.LastSeenAt = u.LastSeenAt
		}
	}
	c.sendJSON(f)
}

func to(id string) string { return strings.ToLower(id) }

// devicesMatch checks that a message is encrypted for exactly the right
// devices, each with its current identity key: every device of the
// recipient, and either all of the sender's other devices or none of them.
// A message to yourself (a copy for your other devices) needs them all.
func (h *Handlers) devicesMatch(ctx context.Context, c *client, recipient string, envs map[string]deviceEnvelope) (bool, error) {
	recipient = to(recipient)
	theirs, err := h.Keys.Devices(ctx, recipient)
	if err != nil {
		return false, err
	}
	mine := theirs
	if recipient != c.userID {
		if mine, err = h.Keys.Devices(ctx, c.userID); err != nil {
			return false, err
		}
	}
	want := func(ds []keys.Device) map[string]string {
		out := map[string]string{}
		for _, d := range ds {
			if d.ID != c.deviceID {
				out[d.ID] = base64.StdEncoding.EncodeToString(d.IdentityKey)
			}
		}
		return out
	}
	required, own := want(theirs), want(mine)
	if len(required) == 0 {
		return false, nil
	}
	covered := 0
	for id, key := range required {
		e, ok := envs[id]
		if !ok || e.IdentityKey != key {
			return false, nil
		}
		covered++
	}
	if len(envs) == covered {
		return true, nil
	}
	for id, key := range own {
		if _, done := required[id]; done {
			continue
		}
		e, ok := envs[id]
		if !ok || e.IdentityKey != key {
			return false, nil
		}
		covered++
	}
	return len(envs) == covered, nil
}

func (h *Handlers) handleReceipt(ctx context.Context, c *client, in inbound) {
	if (in.Status != "delivered" && in.Status != "read") || len(in.MessageIDs) == 0 || len(in.MessageIDs) > 500 {
		c.sendJSON(outbound{Type: "error", Code: "bad_receipt", Error: "invalid receipt"})
		return
	}
	changed, err := h.Store.MarkReceipts(ctx, c.userID, in.MessageIDs, in.Status == "read")
	if err != nil {
		slog.Error("receipt failed", "err", err)
		return
	}
	bySender := map[string][]int64{}
	for _, m := range changed {
		bySender[m.SenderID] = append(bySender[m.SenderID], m.ID)
	}
	at := time.Now().UTC().Format(time.RFC3339Nano)
	var all []int64
	for sender, ids := range bySender {
		b, _ := json.Marshal(outbound{Type: "receipt", Status: in.Status, MessageIDs: ids, At: at, From: c.userID})
		h.Hub.Publish(ctx, sender, b)
		all = append(all, ids...)
	}
	if in.Status == "read" && len(all) > 0 {
		// Your other devices mark the chat read too.
		b, _ := json.Marshal(outbound{Type: "receipt", Status: "read", MessageIDs: all, At: at, From: c.userID})
		h.Hub.Publish(ctx, c.userID, b)
	}
}
