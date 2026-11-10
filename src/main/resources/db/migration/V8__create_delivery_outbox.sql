CREATE TABLE delivery_outbox(

id UUID PRIMARY KEY,
delivery_id UUID NOT NULL,
created_at timestamptz NOT NULL,
published_at timestamptz,

CONSTRAINT fk_delivery_outbox_delivery
FOREIGN KEY(delivery_id) REFERENCES deliveries(id)
);

CREATE INDEX idx_delivery_outbox_unpublished
ON delivery_outbox(created_at, id)
WHERE published_at IS NULL;