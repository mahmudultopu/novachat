// Package chat stores 1:1 messages and delivers them over WebSockets.
package chat

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/mahmudultopu/novachat/server/internal/auth"
)

type Message struct {
	ID          int64   `json:"id"`
	ClientID    string  `json:"client_id"`
	SenderID    string  `json:"sender_id"`
	RecipientID string  `json:"recipient_id"`
	Body        string  `json:"body"`
	Envelope    *string `json:"envelope,omitempty"` // encrypted content from before linked devices
	// SenderDevice sent it; Envelopes holds its content encrypted for each device.
	SenderDevice *string           `json:"sender_device,omitempty"`
	Envelopes    map[string]string `json:"envelopes,omitempty"`
	CreatedAt    time.Time         `json:"created_at"`
	DeliveredAt  *time.Time        `json:"delivered_at"`
	ReadAt       *time.Time        `json:"read_at"`
	// GroupID marks a group event written by the server; Body is its JSON.
	GroupID *string `json:"group_id,omitempty"`
}

type Conversation struct {
	Peer        auth.User `json:"peer"`
	LastMessage Message   `json:"last_message"`
	Unread      int       `json:"unread"`
}

var ErrUnknownRecipient = errors.New("recipient does not exist")

type Store struct {
	DB *pgxpool.Pool
}

const msgCols = `id, client_id::text, sender_id::text, recipient_id::text, body, envelope, created_at, delivered_at, read_at, group_id::text, sender_device::text, envelopes`

func scanMessage(row pgx.Row) (Message, error) {
	var m Message
	var envs []byte
	err := row.Scan(&m.ID, &m.ClientID, &m.SenderID, &m.RecipientID, &m.Body, &m.Envelope, &m.CreatedAt, &m.DeliveredAt, &m.ReadAt, &m.GroupID, &m.SenderDevice, &envs)
	if err == nil && envs != nil {
		err = json.Unmarshal(envs, &m.Envelopes)
	}
	return m, err
}

func collectMessages(rows pgx.Rows, err error) ([]Message, error) {
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Message{}
	for rows.Next() {
		m, err := scanMessage(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// Insert stores a message: either a plain body or end-to-end encrypted
// envelopes, one per device. Sending the same client_id twice (a retry after
// a dropped connection) returns the original message with inserted=false.
func (s *Store) Insert(ctx context.Context, senderID, senderDevice, clientID, recipientID, body string, envelopes map[string]string) (Message, bool, error) {
	var envs *string
	if envelopes != nil {
		b, err := json.Marshal(envelopes)
		if err != nil {
			return Message{}, false, err
		}
		v := string(b)
		envs = &v
	}
	m, err := scanMessage(s.DB.QueryRow(ctx, `
		INSERT INTO messages (client_id, sender_id, sender_device, recipient_id, body, envelopes)
		VALUES ($1, $2, $3, $4, $5, $6::jsonb)
		ON CONFLICT (sender_id, client_id) DO NOTHING
		RETURNING `+msgCols, clientID, senderID, senderDevice, recipientID, body, envs))
	if err == nil {
		return m, true, nil
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23503" {
		return m, false, ErrUnknownRecipient
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return m, false, err
	}
	m, err = scanMessage(s.DB.QueryRow(ctx,
		`SELECT `+msgCols+` FROM messages WHERE sender_id = $1 AND client_id = $2`, senderID, clientID))
	return m, false, err
}

// Querier runs SQL on the pool or inside a transaction.
type Querier interface {
	Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error)
}

// InsertEvents writes one group event (body is its JSON) to every recipient,
// as from actorID. Each copy is an ordinary message, so phones that are
// offline get it through /v1/sync in order with the chat.
func InsertEvents(ctx context.Context, q Querier, groupID, actorID string, recipients []string, body string) ([]Message, error) {
	return collectMessages(q.Query(ctx, `
		INSERT INTO messages (client_id, sender_id, recipient_id, body, group_id)
		SELECT gen_random_uuid(), $1, r, $2, $3 FROM unnest($4::uuid[]) AS r
		RETURNING `+msgCols, actorID, body, groupID, recipients))
}

// MarkReceipts records that the recipient's device got ("delivered") or
// opened ("read") messages. Only messages addressed to recipientID change.
// It returns the messages that changed so their senders can be told.
func (s *Store) MarkReceipts(ctx context.Context, recipientID string, ids []int64, read bool) ([]Message, error) {
	q := `UPDATE messages SET delivered_at = now()
		WHERE recipient_id = $1 AND id = ANY($2) AND delivered_at IS NULL
		RETURNING ` + msgCols
	if read {
		q = `UPDATE messages SET read_at = now(), delivered_at = COALESCE(delivered_at, now())
		WHERE recipient_id = $1 AND id = ANY($2) AND read_at IS NULL
		RETURNING ` + msgCols
	}
	return collectMessages(s.DB.Query(ctx, q, recipientID, ids))
}

// Sync returns messages to or from userID with an id above after, oldest
// first. The app calls it after every reconnect to catch up. A linked
// device starts at the messages sent after it was linked.
func (s *Store) Sync(ctx context.Context, userID, deviceID string, after int64, limit int) ([]Message, error) {
	return collectMessages(s.DB.Query(ctx, `
		SELECT `+msgCols+` FROM messages
		WHERE (recipient_id = $1 OR (sender_id = $1 AND group_id IS NULL))
		  AND id > GREATEST($2, (SELECT first_message_id FROM sessions WHERE id = $3))
		ORDER BY id LIMIT $4`, userID, after, deviceID, limit))
}

// History returns up to limit messages between two users older than before,
// oldest first.
func (s *Store) History(ctx context.Context, userID, peerID string, before int64, limit int) ([]Message, error) {
	msgs, err := collectMessages(s.DB.Query(ctx, `
		SELECT `+msgCols+` FROM messages
		WHERE ((sender_id = $1 AND recipient_id = $2) OR (sender_id = $2 AND recipient_id = $1))
		  AND group_id IS NULL AND id < $3
		ORDER BY id DESC LIMIT $4`, userID, peerID, before, limit))
	for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
		msgs[i], msgs[j] = msgs[j], msgs[i]
	}
	return msgs, err
}

// PresenceAudience returns who should hear that userID came online or went
// offline: the people they chat with, minus anyone they blocked or whom
// their last seen setting leaves out.
func (s *Store) PresenceAudience(ctx context.Context, userID string, limit int) ([]string, error) {
	rows, err := s.DB.Query(ctx, `
		SELECT peer::text FROM (
			SELECT CASE WHEN sender_id = $1 THEN recipient_id ELSE sender_id END AS peer, max(id) AS last
			FROM messages WHERE (sender_id = $1 OR recipient_id = $1) AND group_id IS NULL GROUP BY 1
		) p JOIN users u ON u.id = $1
		WHERE peer <> $1
		  AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker_id = $1 AND b.blocked_id = p.peer)
		  AND (u.last_seen_privacy = 'everyone' OR (u.last_seen_privacy = 'chats' AND EXISTS (
		      SELECT 1 FROM messages m WHERE m.sender_id = $1 AND m.recipient_id = p.peer AND m.group_id IS NULL)))
		ORDER BY last DESC LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// Conversations lists userID's chats, most recent first.
func (s *Store) Conversations(ctx context.Context, userID string) ([]Conversation, error) {
	rows, err := s.DB.Query(ctx, `
		WITH mine AS (
			SELECT m.*, CASE WHEN sender_id = $1 THEN recipient_id ELSE sender_id END AS peer
			FROM messages m WHERE (sender_id = $1 OR recipient_id = $1) AND group_id IS NULL
		), last AS (
			SELECT DISTINCT ON (peer) * FROM mine ORDER BY peer, id DESC
		)
		SELECT last.id, last.client_id::text, last.sender_id::text, last.recipient_id::text, last.body, last.envelope,
		       last.created_at, last.delivered_at, last.read_at,
		       `+auth.VisibleCols("$1")+`,
		       (SELECT count(*) FROM messages x
		         WHERE x.sender_id = last.peer AND x.recipient_id = $1 AND x.read_at IS NULL)
		FROM last JOIN users u ON u.id = last.peer
		ORDER BY last.id DESC LIMIT 500`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Conversation{}
	for rows.Next() {
		var c Conversation
		m := &c.LastMessage
		if err := rows.Scan(&m.ID, &m.ClientID, &m.SenderID, &m.RecipientID, &m.Body, &m.Envelope, &m.CreatedAt, &m.DeliveredAt, &m.ReadAt,
			&c.Peer.ID, &c.Peer.Phone, &c.Peer.Name, &c.Peer.About, &c.Peer.CreatedAt, &c.Peer.AvatarID, &c.Peer.LastSeenAt, &c.Unread); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
