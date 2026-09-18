-- Job queue schema. The jobs table is the queue itself; workers claim rows with
-- SELECT ... FOR UPDATE SKIP LOCKED.

CREATE TABLE jobs (
    id           UUID         PRIMARY KEY,
    type         VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status       VARCHAR(20)  NOT NULL,
    priority     INT          NOT NULL DEFAULT 0,
    attempts     INT          NOT NULL DEFAULT 0,
    max_attempts INT          NOT NULL,
    run_at       TIMESTAMPTZ  NOT NULL,
    locked_at    TIMESTAMPTZ,
    locked_by    VARCHAR(255),
    last_error   TEXT,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_jobs_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD', 'CANCELLED')),
    CONSTRAINT ck_jobs_attempts     CHECK (attempts >= 0),
    CONSTRAINT ck_jobs_max_attempts CHECK (max_attempts >= 1),
    -- A RUNNING job must record who holds it and since when (needed for lease expiry).
    CONSTRAINT ck_jobs_running_locked
        CHECK (status <> 'RUNNING' OR (locked_at IS NOT NULL AND locked_by IS NOT NULL))
);

-- The poller's claim query filters on status = 'PENDING' AND run_at <= now() and orders by
-- priority; this index lets it find due jobs without scanning the whole table.
CREATE INDEX idx_jobs_status_run_at_priority ON jobs (status, run_at, priority);

CREATE TABLE recurring_jobs (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(100) NOT NULL,
    payload     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    cron        VARCHAR(120) NOT NULL,
    next_run_at TIMESTAMPTZ  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uk_recurring_jobs_name UNIQUE (name)
);

-- The recurring scheduler looks up enabled definitions whose next_run_at has passed.
CREATE INDEX idx_recurring_jobs_due ON recurring_jobs (next_run_at) WHERE enabled;
