-- Groups. Messages stay end-to-end encrypted: the sending phone encrypts a
-- copy for every member over their 1:1 sessions, so the server only knows
-- who is in a group, never what is said in it.
CREATE TABLE groups (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name             text NOT NULL,
    description      text NOT NULL DEFAULT '',
    avatar_id        uuid REFERENCES avatars (id) ON DELETE SET NULL,
    created_by       uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    invite_code      text UNIQUE,
    only_admins_send boolean NOT NULL DEFAULT false,
    only_admins_edit boolean NOT NULL DEFAULT false
);

CREATE TABLE group_members (
    group_id  uuid NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role      text NOT NULL DEFAULT 'member' CHECK (role IN ('admin', 'member')),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX group_members_user_idx ON group_members (user_id);

-- Set on group events ("Ana added Ben"), which the server writes as plain
-- messages to each member so offline phones catch up through /v1/sync.
ALTER TABLE messages ADD COLUMN group_id uuid REFERENCES groups (id) ON DELETE CASCADE;
