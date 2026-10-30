CREATE TABLE webhook_subscriptions(

    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL,
    event_type varchar(100) NOT NULL,
    FOREIGN KEY(endpoint_id) REFERENCES webhook_endpoints(id),

    CONSTRAINT uq_webhook_subscriptions_endpoint_event_type
    UNIQUE (endpoint_id, event_type)
);

