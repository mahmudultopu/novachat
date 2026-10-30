-- Linked devices: every session is a device with its own encryption keys,
-- and every message carries one envelope per device that may read it.
ALTER TABLE sessions ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE sessions ADD CONSTRAINT sessions_id_key UNIQUE (id);
-- A device linked from the main phone, rather than signed in by SMS.
ALTER TABLE sessions ADD COLUMN linked boolean NOT NULL DEFAULT false;
-- A linked device only gets messages sent after it was linked.
ALTER TABLE sessions ADD COLUMN first_message_id bigint NOT NULL DEFAULT 0;

-- Keys move from one set per user to one set per device. Apps publish
-- again on their next connection.
DROP TABLE one_time_prekeys;
DROP TABLE signed_prekeys;
DROP TABLE identity_keys;

CREATE TABLE device_keys (
    device_id     uuid PRIMARY KEY REFERENCES sessions (id) ON DELETE CASCADE,
    user_id       uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    identity_key  bytea NOT NULL, -- X25519, used in X3DH
    signing_key   bytea NOT NULL, -- Ed25519, signs the prekeys
    spk_id        integer NOT NULL,
    spk           bytea NOT NULL,
    spk_signature bytea NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX device_keys_user_idx ON device_keys (user_id);

-- Each one-time prekey is handed out once, then deleted.
CREATE TABLE device_prekeys (
    device_id  uuid NOT NULL REFERENCES device_keys (device_id) ON DELETE CASCADE,
    key_id     integer NOT NULL,
    public_key bytea NOT NULL,
    PRIMARY KEY (device_id, key_id)
);

ALTER TABLE messages ADD COLUMN sender_device uuid;
-- Device ID to envelope, for the recipient's devices and the sender's others.
ALTER TABLE messages ADD COLUMN envelopes jsonb;
