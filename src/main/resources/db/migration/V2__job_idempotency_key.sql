-- Client-supplied Idempotency-Key: submitting the same key twice returns the original job
-- instead of enqueuing a duplicate. The unique index is what makes this safe under concurrent
-- submissions; it is partial because most jobs are submitted without a key.

ALTER TABLE jobs ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX uk_jobs_idempotency_key ON jobs (idempotency_key) WHERE idempotency_key IS NOT NULL;
