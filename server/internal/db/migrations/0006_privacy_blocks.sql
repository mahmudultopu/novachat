-- Privacy settings, blocking and reports.
--
-- Who may see a user's last seen (and online), profile photo and about:
-- 'everyone', 'chats' (people this user has messaged) or 'nobody'.
ALTER TABLE users ADD COLUMN last_seen_privacy text NOT NULL DEFAULT 'everyone'
    CHECK (last_seen_privacy IN ('everyone', 'chats', 'nobody'));
ALTER TABLE users ADD COLUMN photo_privacy text NOT NULL DEFAULT 'everyone'
    CHECK (photo_privacy IN ('everyone', 'chats', 'nobody'));
ALTER TABLE users ADD COLUMN about_privacy text NOT NULL DEFAULT 'everyone'
    CHECK (about_privacy IN ('everyone', 'chats', 'nobody'));
-- Kept here so it survives a reinstall; the phones apply it.
ALTER TABLE users ADD COLUMN read_receipts boolean NOT NULL DEFAULT true;

-- A blocked user can't see the blocker's photo, about, last seen or online.
-- The blocker's phone drops their messages and calls (it can tell a direct
-- message from a group copy only after decrypting it).
CREATE TABLE blocks (
    blocker_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);

-- Reports for whoever runs the server to review. Messages are end-to-end
-- encrypted, so only the reason the user picked is known.
CREATE TABLE reports (
    id          bigserial PRIMARY KEY,
    reporter_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reported_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason      text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reports_reported_idx ON reports (reported_id, created_at);
