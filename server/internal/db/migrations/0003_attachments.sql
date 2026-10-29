-- Encrypted attachments (photos, videos, voice notes, documents). Phones
-- encrypt every file with a fresh key before upload, and the key travels
-- only inside the end-to-end encrypted message, so these bytes are opaque.
-- This simple store keeps them in Postgres; move to S3-compatible storage
-- (R2, B2, MinIO) once the files outgrow the database.
CREATE TABLE attachments (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    size       integer NOT NULL,
    data       bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX attachments_created_idx ON attachments (created_at);
