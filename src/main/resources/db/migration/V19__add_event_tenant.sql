INSERT INTO tenants(id, name, created_at)
SELECT
 '00000000-0000-0000-0000-000000000001'::UUID,
    'Legacy workspace',
    CURRENT_TIMESTAMP
WHERE EXISTS (SELECT 1 from events)
ON CONFLICT(id) DO NOTHING;

ALTER TABLE events
    ADD COLUMN tenant_id UUID;

UPDATE events
SET tenant_id = '00000000-0000-0000-0000-000000000001'::UUID
WHERE tenant_id IS NULL;

ALTER TABLE events
    ADD CONSTRAINT fk_events_tenants
    FOREIGN KEY (tenant_id) REFERENCES tenants(id);

CREATE INDEX idx_events_tenant_id
    ON events(tenant_id);