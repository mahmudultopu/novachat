CREATE TABLE users (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone      text NOT NULL UNIQUE,
    name       text NOT NULL DEFAULT '',
    about      text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now()
);

-- One row per signed-in device. Only a SHA-256 hash of the token is stored,
-- so a database leak does not hand out working sessions.
CREATE TABLE sessions (
    token_hash   bytea PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_name  text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sessions_user_idx ON sessions (user_id);

-- 1:1 text messages. Bodies are plain text in this first slice; they become
-- opaque end-to-end encrypted envelopes once the crypto layer lands.
CREATE TABLE messages (
    id           bigserial PRIMARY KEY,
    client_id    uuid NOT NULL,
    sender_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recipient_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body         text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz,
    read_at      timestamptz,
    UNIQUE (sender_id, client_id)
);
CREATE INDEX messages_recipient_idx ON messages (recipient_id, id);
CREATE INDEX messages_sender_idx ON messages (sender_id, id);
