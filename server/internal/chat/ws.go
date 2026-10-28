package chat

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gorilla/websocket"

	"github.com/mahmudultopu/novachat/server/internal/auth"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingInterval   = 25 * time.Second
	maxFrameBytes  = 64 << 10
	maxBodyRunes   = 4096
	sendBufferSize = 256
)

// Frames sent by the app.
type inbound struct {
	Type       string  `json:"type"` // "send", "receipt" or "typing"
	ClientID   string  `json:"client_id,omitempty"`
	To         string  `json:"to,omitempty"`
	Body       string  `json:"body,omitempty"`
	Status     string  `json:"status,omitempty"` // "delivered" or "read"
	MessageIDs []int64 `json:"message_ids,omitempty"`
}

// Frames sent by the server.
type outbound struct {
	Type       string   `json:"type"` // "ready", "message", "receipt", "typing" or "error"
	UserID     string   `json:"user_id,omitempty"`
	Message    *Message `json:"message,omitempty"`
	Status     string   `json:"status,omitempty"`
	MessageIDs []int64  `json:"message_ids,omitempty"`
	At         string   `json:"at,omitempty"`
	From       string   `json:"from,omitempty"`
	ClientID   string   `json:"client_id,omitempty"`
	Code       string   `json:"code,omitempty"`
	Error      string   `json:"error,omitempty"`
}

var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	// The app authenticates with a bearer token, not cookies, so a
	// cross-origin page cannot ride on a user's session.
	CheckOrigin: func(*http.Request) bool { return true },
}

type client struct {
	userID string
	conn   *websocket.Conn
	send   chan []byte
	cancel context.CancelFunc
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
	c := &client{userID: auth.UserID(r.Context()), conn: conn, send: make(chan []byte, sendBufferSize), cancel: cancel}
	if err := h.Hub.add(ctx, c); err != nil {
		slog.Error("hub add failed", "err", err)
		cancel()
		conn.Close()
		return
	}
	defer h.Hub.remove(c)

	go c.writeLoop(ctx)
	c.sendJSON(outbound{Type: "ready", UserID: c.userID})
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
		case "typing":
			if auth.IsUUID(in.To) && in.To != c.userID {
				b, _ := json.Marshal(outbound{Type: "typing", From: c.userID})
				h.Hub.Publish(ctx, in.To, b)
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
	switch {
	case !auth.IsUUID(in.ClientID):
		fail("bad_client_id", "client_id must be a UUID")
		return
	case !auth.IsUUID(in.To):
		fail("bad_recipient", "to must be a user id")
		return
	case body == "":
		fail("empty", "message is empty")
		return
	case utf8.RuneCountInString(body) > maxBodyRunes:
		fail("too_long", "message is too long")
		return
	}
	m, inserted, err := h.Store.Insert(ctx, c.userID, strings.ToLower(in.ClientID), in.To, body)
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
	for sender, ids := range bySender {
		b, _ := json.Marshal(outbound{Type: "receipt", Status: in.Status, MessageIDs: ids, At: at, From: c.userID})
		h.Hub.Publish(ctx, sender, b)
	}
}
