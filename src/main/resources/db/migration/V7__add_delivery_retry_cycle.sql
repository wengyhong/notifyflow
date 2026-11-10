ALTER TABLE deliveries
    ADD COLUMN retry_cycle_start_attempt integer NOT NULL DEFAULT 1;

ALTER TABLE deliveries
    ADD CONSTRAINT ck_deliveries_retry_cycle_start_attempt_positive
    CHECK(retry_cycle_start_attempt > 0);