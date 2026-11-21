ALTER TABLE events
    DROP CONSTRAINT uq_events_idempotency_key;

ALTER TABLE events
    ADD CONSTRAINT uq_events_tenant_idempotency_key
    UNIQUE(tenant_id, idempotency_key);