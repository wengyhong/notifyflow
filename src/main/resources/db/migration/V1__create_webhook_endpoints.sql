CREATE TABLE webhook_endpoints(
    id UUID PRIMARY KEY,
    name varchar(100) NOT NULL,
    url text NOT NULL,
    enabled boolean NOT NULL default true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);