CREATE TABLE deliveries(

id UUID PRIMARY KEY,
event_id UUID NOT NULL,
endpoint_id UUID NOT NULL,
FOREIGN KEY (event_id) REFERENCES events(id),
FOREIGN KEY (endpoint_id) REFERENCES webhook_endpoints(id),
status varchar(30) NOT NULL,
created_at timestamptz NOT NULL,

CONSTRAINT uq_deliveries_event_endpoint UNIQUE(event_id, endpoint_id)
);