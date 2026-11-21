ALTER TABLE webhook_endpoints
    ADD COLUMN tenant_id UUID;

UPDATE webhook_endpoints
SET tenant_id = '00000000-0000-0000-0000-000000000001'::UUID
WHERE tenant_id IS NULL;

ALTER TABLE webhook_endpoints
    ADD CONSTRAINT fk_webhook_endpoints_tenants
    FOREIGN KEY(tenant_id) REFERENCES tenants(id);

CREATE INDEX idx_webhook_endpoints_tenant_id
    ON webhook_endpoints(tenant_id);