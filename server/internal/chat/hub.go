package chat

import (
	"context"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// Hub routes events to users' open connections. Every server instance
// shares one Redis subscription and subscribes to a user's channel (and
// each device's own channel) while it has a connection here, so a message
// published by any instance reaches the right phones. (NATS JetStream
// replaces this at scale.)
type Hub struct {
	rdb *redis.Client
	ps  *redis.PubSub

	mu sync.Mutex
	// Channel name to the connections listening on it.
	conns map[string]map[*client]struct{}
}

func NewHub(ctx context.Context, rdb *redis.Client) *Hub {
	h := &Hub{rdb: rdb, ps: rdb.Subscribe(ctx), conns: map[string]map[*client]struct{}{}}
	go h.run()
	return h
}

// presenceTTL is refreshed every presenceRefresh while a phone is connected.
const (
	presenceTTL     = 75 * time.Second
	presenceRefresh = 30 * time.Second
)

func channel(userID string) string { return "nc:user:" + userID }

func deviceChannel(deviceID string) string { return "nc:device:" + deviceID }

// kick is published on a device's channel when it is signed out.
const kick = "kick"

func (h *Hub) run() {
	for msg := range h.ps.Channel() {
		h.mu.Lock()
		for c := range h.conns[msg.Channel] {
			if strings.HasPrefix(msg.Channel, "nc:device:") && msg.Payload == kick {
				c.cancel()
				continue
			}
			c.enqueue([]byte(msg.Payload))
		}
		h.mu.Unlock()
	}
}

// Kick closes a signed-out device's connections, on any instance.
func (h *Hub) Kick(ctx context.Context, deviceID string) {
	if err := h.rdb.Publish(ctx, deviceChannel(deviceID), kick).Err(); err != nil {
		slog.Error("kick failed", "device", deviceID, "err", err)
	}
}

// Publish sends an event to every connection userID has, on any instance.
func (h *Hub) Publish(ctx context.Context, userID string, payload []byte) {
	if err := h.rdb.Publish(ctx, channel(userID), payload).Err(); err != nil {
		slog.Error("publish failed", "user", userID, "err", err)
	}
}

// add registers a connection on its user's and its device's channels.
func (h *Hub) add(ctx context.Context, c *client) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, ch := range []string{channel(c.userID), deviceChannel(c.deviceID)} {
		set := h.conns[ch]
		if set == nil {
			if err := h.ps.Subscribe(ctx, ch); err != nil {
				return err
			}
			set = map[*client]struct{}{}
			h.conns[ch] = set
		}
		set[c] = struct{}{}
	}
	return nil
}

// remove drops a connection.
func (h *Hub) remove(c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, ch := range []string{channel(c.userID), deviceChannel(c.deviceID)} {
		set := h.conns[ch]
		delete(set, c)
		if len(set) == 0 {
			delete(h.conns, ch)
			if err := h.ps.Unsubscribe(context.Background(), ch); err != nil {
				slog.Error("unsubscribe failed", "channel", ch, "err", err)
			}
		}
	}
}

// anyActive reports whether userID has a connection here with the app on screen.
func (h *Hub) anyActive(userID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.conns[channel(userID)] {
		if c.active.Load() {
			return true
		}
	}
	return false
}

func presenceKey(userID string) string { return "nc:online:" + userID }

// MarkOnline records that userID has a live connection. The key expires on
// its own if this instance dies, so nobody stays "online" forever.
func (h *Hub) MarkOnline(ctx context.Context, userID string) {
	if err := h.rdb.Set(ctx, presenceKey(userID), "1", presenceTTL).Err(); err != nil {
		slog.Error("mark online failed", "user", userID, "err", err)
	}
}

func (h *Hub) MarkOffline(ctx context.Context, userID string) {
	h.rdb.Del(ctx, presenceKey(userID))
}

func (h *Hub) IsOnline(ctx context.Context, userID string) bool {
	n, err := h.rdb.Exists(ctx, presenceKey(userID)).Result()
	return err == nil && n > 0
}

func (h *Hub) Close() error { return h.ps.Close() }
