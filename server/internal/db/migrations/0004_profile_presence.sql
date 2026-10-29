-- Profile photos and "last seen".
CREATE TABLE avatars (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    data       bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE users ADD COLUMN avatar_id uuid REFERENCES avatars (id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN last_seen_at timestamptz;
