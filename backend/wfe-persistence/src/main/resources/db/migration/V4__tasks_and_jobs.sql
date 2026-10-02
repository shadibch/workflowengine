-- ===========================================================================
--  V4 - Human tasks and the job queue
--
--  Assignment model (hybrid identity decision)
--  ---------------------------------------------
--  A user task names a direct assignee, candidate users, or candidate roles.
--  Candidate roles are resolved against iam_user_role at *task creation* time
--  and snapshotted into `candidate_role_ids`, then re-validated against the DB
--  at *claim* time. Two consequences, both deliberate:
--    * a role change never retroactively rewrites who could see an open task
--      (the snapshot + `assignment_snapshot` preserve the audit trail), and
--    * a revoked role blocks a claim the moment it is revoked.
-- ===========================================================================

CREATE TABLE wf_task (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           TEXT        NOT NULL DEFAULT 'default',
    instance_id         BIGINT      NOT NULL REFERENCES wf_instance (id) ON DELETE CASCADE,
    -- The token parked on this task. A token completes only when its task does.
    token_id            BIGINT      REFERENCES wf_token (id) ON DELETE CASCADE,
    node_id             TEXT        NOT NULL,
    name                TEXT        NOT NULL,
    description         TEXT,
    task_type           TEXT        NOT NULL DEFAULT 'USER'
                                    CHECK (task_type IN ('USER', 'APPROVAL', 'FORM', 'NOTIFICATION')),
    form_schema         JSONB,
    -- Snapshot of the variables rendered in the form, so a later variable
    -- change cannot silently alter what an approver was asked to decide.
    form_context        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    form_key            TEXT,
    -- NULL while the task sits in a candidate queue.
    assignee_id         BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    candidate_role_ids  BIGINT[]    NOT NULL DEFAULT '{}',
    candidate_user_ids  BIGINT[]    NOT NULL DEFAULT '{}',
    -- The resolver's inputs, retained for audit.
    assignment_snapshot JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status              TEXT        NOT NULL DEFAULT 'CREATED'
                                    CHECK (status IN ('CREATED', 'CLAIMED', 'COMPLETED',
                                                      'CANCELLED', 'FAILED', 'EXPIRED')),
    priority            INTEGER     NOT NULL DEFAULT 50,
    claimed_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    due_at              TIMESTAMPTZ,
    -- Variable to branch on once completed, e.g. `approved`.
    outcome_variable    TEXT,
    outcome             TEXT,
    form_data           JSONB,
    reminder_sent_at    TIMESTAMPTZ,
    escalated_at        TIMESTAMPTZ,
    escalation_task_id  BIGINT      REFERENCES wf_task (id) ON DELETE SET NULL,
    delegated_from_id   BIGINT      REFERENCES wf_task (id) ON DELETE SET NULL,
    created_by          BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wf_task_end_state CHECK (
        (status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
            AND completed_at IS NOT NULL) OR
        (status IN ('CREATED', 'CLAIMED'))
    ),
    -- A claim is exclusive: an assignee implies a claimed task.
    CONSTRAINT ck_wf_task_claim_exclusive CHECK (assignee_id IS NULL OR status <> 'CREATED')
);

-- "My tasks" inbox. Partial on the two open states keeps the index tiny.
CREATE INDEX ix_wf_task_inbox_assignee
    ON wf_task (tenant_id, assignee_id, status, due_at)
    WHERE status IN ('CREATED', 'CLAIMED');

-- GIN over the role array: the candidate-queue query without a seq scan.
CREATE INDEX ix_wf_task_inbox_candidates
    ON wf_task USING gin (candidate_role_ids)
    WHERE status = 'CREATED';

CREATE INDEX ix_wf_task_instance
    ON wf_task (instance_id, status);

-- SLA breach report and the escalation job's scan.
CREATE INDEX ix_wf_task_sla
    ON wf_task (tenant_id, due_at)
    WHERE status IN ('CREATED', 'CLAIMED') AND due_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Comments / decision log on a task.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_task_comment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   TEXT        NOT NULL DEFAULT 'default',
    task_id     BIGINT      NOT NULL REFERENCES wf_task (id) ON DELETE CASCADE,
    author_id   BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    body        TEXT        NOT NULL,
    kind        TEXT        NOT NULL DEFAULT 'COMMENT'
                            CHECK (kind IN ('COMMENT', 'DECISION', 'SYSTEM')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_wf_task_comment_task ON wf_task_comment (task_id, created_at);

-- ---------------------------------------------------------------------------
-- Job queue: timers, service retries, async continuations, reminders.
--
--  Claimed with `FOR UPDATE SKIP LOCKED`, so the engine scales horizontally
--  with no broker in the hot path. `dedup_key` is the idempotency guarantee:
--  re-scheduling logically identical work is a no-op rather than a duplicate
--  timer firing twice.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_job (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    job_type        TEXT        NOT NULL
                                CHECK (job_type IN ('TIMER', 'SERVICE_RETRY', 'ASYNC_CONTINUE',
                                                   'REMINDER', 'ESCALATION', 'MESSAGE_PUBLISH',
                                                   'REPLY_TIMEOUT', 'TERMINATE_CHECK', 'CLEANUP')),
    instance_id     BIGINT      REFERENCES wf_instance (id) ON DELETE CASCADE,
    task_id         BIGINT      REFERENCES wf_task (id) ON DELETE CASCADE,
    node_id         TEXT,
    run_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    dedup_key       TEXT        NOT NULL,
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status          TEXT        NOT NULL DEFAULT 'READY'
                                CHECK (status IN ('READY', 'CLAIMED', 'SUCCEEDED', 'FAILED',
                                                  'CANCELLED', 'DEAD')),
    attempts        INTEGER     NOT NULL DEFAULT 0,
    max_attempts    INTEGER     NOT NULL DEFAULT 3,
    -- delay_n = min(retry_base_ms * retry_mult^n, retry_max_ms)
    retry_base_ms   INTEGER     NOT NULL DEFAULT 1000,
    retry_max_ms    INTEGER     NOT NULL DEFAULT 300000,
    retry_mult      NUMERIC(5, 2) NOT NULL DEFAULT 2.0,
    -- A job claimed by a crashed worker would stay CLAIMED forever, so claims
    -- expire and the row returns to READY.
    locked_by       TEXT,
    locked_at       TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_wf_job_dedup
    ON wf_job (tenant_id, dedup_key)
    WHERE status IN ('READY', 'CLAIMED');

-- The dispatcher's hot path: poll READY jobs that are due.
CREATE INDEX ix_wf_job_poll
    ON wf_job (run_at)
    WHERE status = 'READY';

-- Reclaim sweeper for jobs whose worker died mid-execution.
CREATE INDEX ix_wf_job_reclaim
    ON wf_job (locked_at)
    WHERE status = 'CLAIMED';
