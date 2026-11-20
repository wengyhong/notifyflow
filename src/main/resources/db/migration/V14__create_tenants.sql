CREATE TABLE
    tenants (
        id UUID PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL
    );