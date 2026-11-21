INSERT INTO tenants (id, name, created_at)
SELECT
    '00000000-0000-0000-0000-000000000001'::UUID,
    'Legacy workspace',
    CURRENT_TIMESTAMP
WHERE EXISTS (
    SELECT 1 FROM webhook_endpoints WHERE tenant_id IS NULL
)
OR EXISTS (
    SELECT 1 FROM events WHERE tenant_id IS NULL
)
ON CONFLICT (id) DO NOTHING;

UPDATE webhook_endpoints
SET tenant_id = '00000000-0000-0000-0000-000000000001'::UUID
WHERE tenant_id IS NULL;

UPDATE events
SET tenant_id = '00000000-0000-0000-0000-000000000001'::UUID
WHERE tenant_id IS NULL;

ALTER TABLE webhook_endpoints
    ALTER COLUMN tenant_id SET NOT NULL;

ALTER TABLE events
    ALTER COLUMN tenant_id SET NOT NULL;