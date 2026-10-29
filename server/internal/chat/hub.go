package chat

import (
	"context"
	"log/slog"
	"sync"

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

func (h *Hub) add(ctx context.Context, c *client) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[c.userID]
	if set == nil {
		if err := h.ps.Subscribe(ctx, channel(c.userID)); err != nil {
			return err
		}
		set = map[*client]struct{}{}
		h.conns[c.userID] = set
	}
	set[c] = struct{}{}
	return nil
}

func (h *Hub) remove(c *client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set := h.conns[c.userID]
	delete(set, c)
	if len(set) == 0 {
		delete(h.conns, c.userID)
		if err := h.ps.Unsubscribe(context.Background(), channel(c.userID)); err != nil {
			slog.Error("unsubscribe failed", "user", c.userID, "err", err)
		}
	}
}

func (h *Hub) Close() error { return h.ps.Close() }
