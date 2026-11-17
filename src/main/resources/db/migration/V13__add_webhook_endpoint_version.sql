ALTER TABLE webhook_endpoints
    ADD COLUMN version bigint NOT NULL default 0;