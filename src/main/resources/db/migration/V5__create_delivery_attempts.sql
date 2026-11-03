CREATE TABLE delivery_attempts(

    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL,
    attempt_number integer NOT NULL CHECK(attempt_number > 0),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    response_status integer,
    error_message text,
    FOREIGN KEY (delivery_id) REFERENCES deliveries(id),

    CONSTRAINT uq_delivery_attempts_delivery_number UNIQUE(delivery_id, attempt_number)
);