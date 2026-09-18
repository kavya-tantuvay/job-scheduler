-- One row per finished execution attempt, so a failed or dead-lettered job can be debugged from
-- its full history (which worker ran it, how long it took, the error and stack trace of each
-- attempt) rather than only the last error. A row is written in the same transaction as the
-- attempt's status update, and only by whoever still owns the claim, so each attempt number
-- appears at most once.

CREATE TABLE job_attempts (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id      UUID         NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    attempt     INT          NOT NULL,
    worker_id   VARCHAR(255) NOT NULL,
    started_at  TIMESTAMPTZ  NOT NULL,
    finished_at TIMESTAMPTZ  NOT NULL,
    outcome     VARCHAR(20)  NOT NULL,
    error       TEXT,
    stack_trace TEXT,

    CONSTRAINT ck_job_attempts_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'LEASE_EXPIRED')),
    CONSTRAINT uk_job_attempts_job_attempt UNIQUE (job_id, attempt)
);
