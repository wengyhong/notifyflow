CREATE TABLE
    tenant_memberships (
        id UUID PRIMARY KEY,
        tenant_id UUID NOT NULL REFERENCES tenants (id),
        user_id UUID NOT NULL REFERENCES app_users (id),
        role VARCHAR(20) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
         CONSTRAINT uq_tenant_memberships_tenant_user UNIQUE (tenant_id, user_id),
          CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('ADMIN', 'VIEWER'))
    );

CREATE INDEX idx_tenant_memberships_user_id ON tenant_memberships (user_id);