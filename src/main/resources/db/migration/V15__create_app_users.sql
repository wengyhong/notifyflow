CREATE TABLE app_users(
    id UUID PRIMARY KEY,
    email varchar(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uq_app_users_email_lower
ON app_users (LOWER(email));