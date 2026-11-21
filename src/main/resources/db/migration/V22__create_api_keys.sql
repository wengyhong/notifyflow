CREATE TABLE
    api_keys (
        id UUID PRIMARY KEY,
        tenant_id UUID NOT NULL,
        name VARCHAR(100) NOT NULL,
        key_hash VARCHAR(64) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        revoked_at TIMESTAMPTZ,

        CONSTRAINT fk_api_keys_tenants
            FOREIGN KEY (tenant_id) REFERENCES tenants(id),

        CONSTRAINT uq_api_keys_key_hash
            UNIQUE(key_hash)
    );

    CREATE INDEX idx_api_keys_tenant_id
        ON api_keys(tenant_id);