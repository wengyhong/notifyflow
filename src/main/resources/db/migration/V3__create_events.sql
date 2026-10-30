CREATE TABLE events(

id UUID PRIMARY KEY,
event_type varchar(100) NOT NULL,
payload jsonb NOT NULL,
idempotency_key varchar(200) NOT NULL,
created_at timestamptz NOT NULL,

CONSTRAINT uq_events_idempotency_key UNIQUE(idempotency_key)

);