CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE webhook_endpoints
SET signing_secret = encode(gen_random_bytes(32), 'base64')
WHERE signing_secret IS NULL;