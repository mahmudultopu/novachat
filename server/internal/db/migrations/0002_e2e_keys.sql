-- End-to-end encryption key directory (X3DH). The server only stores public
-- keys; private keys never leave the phone.
CREATE TABLE identity_keys (
    user_id      uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    identity_key bytea NOT NULL, -- X25519, used in X3DH
    signing_key  bytea NOT NULL, -- Ed25519, signs the prekeys
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE signed_prekeys (
    user_id    uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    key_id     integer NOT NULL,
    public_key bytea NOT NULL,
    signature  bytea NOT NULL
);

-- Each one-time prekey is handed out once, then deleted.
CREATE TABLE one_time_prekeys (
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_id     integer NOT NULL,
    public_key bytea NOT NULL,
    PRIMARY KEY (user_id, key_id)
);

-- Encrypted messages carry an opaque envelope instead of a plain body.
ALTER TABLE messages ADD COLUMN envelope text;
