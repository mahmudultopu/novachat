package chat

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// Hub routes events to users' open connections. Every server instance
// shares one Redis subscription and subscribes to a user's channel while
// that user has a connection here, so a message published by any instance
// reaches the right phones. (NATS JetStream replaces this at scale.)
type Hub struct {
	rdb *redis.Client
	ps  *redis.PubSub

	mu    sync.Mutex
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

func (h *Hub) run() {
	for msg := range h.ps.Channel() {
		userID := msg.Channel[len("nc:user:"):]
		h.mu.Lock()
		for c := range h.conns[userID] {
			c.enqueue([]byte(msg.Payload))
		}
		h.mu.Unlock()
	}
}

// Publish sends an event to every connection userID has, on any instance.
func (h *Hub) Publish(ctx context.Context, userID string, payload []byte) {
	if err := h.rdb.Publish(ctx, channel(userID), payload).Err(); err != nil {
		slog.Error("publish failed", "user", userID, "err", err)
	}
}

// add registers a connection. first reports whether it is the user's only
// connection on this instance.
func (h *Hub) add(ctx context.Context, c *client) (first bool, err error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[c.userID]
	if set == nil {
		if err := h.ps.Subscribe(ctx, channel(c.userID)); err != nil {
			return false, err
		}
		set = map[*client]struct{}{}
		h.conns[c.userID] = set
	}
	set[c] = struct{}{}
	return len(set) == 1, nil
}

// remove drops a connection. last reports whether the user has no other
// connection on this instance.
func (h *Hub) remove(c *client) (last bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[c.userID]
	delete(set, c)
	if len(set) == 0 {
		delete(h.conns, c.userID)
		if err := h.ps.Unsubscribe(context.Background(), channel(c.userID)); err != nil {
			slog.Error("unsubscribe failed", "user", c.userID, "err", err)
		}
		return true
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
