ALTER TABLE idempotency_keys
    ADD COLUMN owner_token uuid;

CREATE INDEX idx_idempotency_processing_lease
    ON idempotency_keys(locked_until)
    WHERE state = 'PROCESSING';
