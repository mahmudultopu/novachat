-- Two-step verification: a 6-digit PIN asked for when signing in on a new
-- phone, after the SMS code. Wrong PINs lock sign-in for a while; someone
-- who forgot it can ask for a reset, which takes effect after 7 days.
ALTER TABLE users ADD COLUMN pin_hash text;
ALTER TABLE users ADD COLUMN pin_failures integer NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN pin_locked_until timestamptz;
ALTER TABLE users ADD COLUMN pin_reset_at timestamptz;
