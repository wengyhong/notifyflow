ALTER TABLE deliveries
    ADD COLUMN next_attempt_at timestamptz;

CREATE INDEX idx_deliveries_due_retries
    ON deliveries(next_attempt_at, id)
    WHERE status = 'RETRY_SCHEDULED';