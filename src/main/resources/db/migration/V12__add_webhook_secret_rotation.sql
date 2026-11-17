ALTER TABLE webhook_endpoints
    ADD COLUMN previous_signing_secret varchar(44),
    ADD COLUMN previous_signing_secret_expires_at timestamptz,
    ADD CONSTRAINT ck_webhook_endpoints_previous_secret_pair CHECK(
    (
        previous_signing_secret IS NULL AND previous_signing_secret_expires_at IS NULL
    )
    OR
    (
        previous_signing_secret IS NOT NULL AND previous_signing_secret_expires_at IS NOT NULL
    )
    );