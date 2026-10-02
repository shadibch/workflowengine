-- ===========================================================================
--  V3 - Runtime: instance, token, variable
--
--  Engine invariants are pushed down into the schema so a bug cannot silently
--  corrupt execution state:
--    * one live token per (instance, node, mi_index) - enforced by a partial
--      UNIQUE index, which is what makes gateway joins release exactly once.
--    * `wf_token.lock_version` / `wf_variable.lock_version` give optimistic
--      concurrency: a lost update aborts the transaction and the job is
--      retried, so no node is ever executed twice.
--    * end-state checks make "completed but no ended_at" unrepresentable.
-- ===========================================================================

CREATE TABLE wf_instance (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               TEXT        NOT NULL DEFAULT 'default',
    definition_id           BIGINT      NOT NULL REFERENCES wf_definition (id) ON DELETE RESTRICT,
    version_id              BIGINT      NOT NULL REFERENCES wf_version (id) ON DELETE RESTRICT,
    -- Caller-supplied idempotency handle. UNIQUE per tenant, so a retried
    -- "start instance" request cannot create a duplicate process.
    business_key            TEXT,
    status                  TEXT        NOT NULL DEFAULT 'RUNNING'
                                        CHECK (status IN ('RUNNING', 'SUSPENDED', 'COMPLETED',
                                                          'CANCELLED', 'FAILED',
                                                          'EXTERNALLY_TERMINATED')),
    -- Process-scope variables, denormalised so list screens and dashboard
    -- filters do not have to aggregate wf_variable.
    variables_snapshot      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    start_variables         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Variables flagged PII: excluded from logs, simulation exports and SSE.
    pii_variable_names      TEXT[]      NOT NULL DEFAULT '{}',
    -- Registered by a message start event, so a duplicate message does not
    -- spawn a second instance.
    correlation_key         TEXT,
    -- Highest sequence_no written to wf_event_log for this instance. Kept on
    -- the row to allocate a gap-free per-instance history without a lock.
    history_seq             BIGINT      NOT NULL DEFAULT 0,
    started_by              BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    completed_by            BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at                TIMESTAMPTZ,
    last_activity_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,
    CONSTRAINT ck_wf_instance_end_state CHECK (
        (status IN ('COMPLETED', 'CANCELLED', 'FAILED', 'EXTERNALLY_TERMINATED')
            AND ended_at IS NOT NULL) OR
        (status IN ('RUNNING', 'SUSPENDED') AND ended_at IS NULL)
    )
);

-- Idempotent start. Includes CANCELLED/FAILED instances so a restart with the
-- same business key is rejected rather than creating a parallel process.
CREATE UNIQUE INDEX ux_wf_instance_business_key
    ON wf_instance (tenant_id, business_key)
    WHERE business_key IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX ix_wf_instance_listing
    ON wf_instance (tenant_id, status, started_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_wf_instance_definition
    ON wf_instance (definition_id, status);

-- Dashboard filter over variables without a sequential scan.
CREATE INDEX ix_wf_instance_vars_gin
    ON wf_instance USING gin (variables_snapshot jsonb_path_ops);

CREATE INDEX ix_wf_instance_correlation
    ON wf_instance (tenant_id, correlation_key)
    WHERE correlation_key IS NOT NULL;

-- "Stuck instance" detector: running far longer than its own SLA.
CREATE INDEX ix_wf_instance_stalled
    ON wf_instance (last_activity_at)
    WHERE status = 'RUNNING';

-- ---------------------------------------------------------------------------
-- Tokens. One row per active execution position. A token is deleted as it
-- advances; wf_event_log is the durable record of what it did.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_token (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               TEXT        NOT NULL DEFAULT 'default',
    instance_id             BIGINT      NOT NULL REFERENCES wf_instance (id) ON DELETE CASCADE,
    -- Enclosing scope: sub-process, call activity or multi-instance body.
    -- NULL for the process scope itself.
    scope_instance_id       BIGINT      REFERENCES wf_instance (id) ON DELETE CASCADE,
    parent_token_id         BIGINT      REFERENCES wf_token (id) ON DELETE CASCADE,
    -- BPMN element id in this version's XML.
    node_id                 TEXT        NOT NULL,
    node_type               TEXT        NOT NULL,
    state                   TEXT        NOT NULL DEFAULT 'ACTIVE'
                                        CHECK (state IN ('ACTIVE', 'WAITING', 'BLOCKED', 'SUSPENDED')),
    -- Multi-instance bookkeeping; NULL for ordinary execution.
    mi_index                INTEGER,
    mi_total                INTEGER,
    mi_is_sequential        BOOLEAN,
    -- Incoming flows already satisfied at a gateway join. The join releases
    -- when the set of satisfied flow ids is complete, which is the correct
    -- rule for inclusive gateways (>= 1 is not sufficient).
    join_satisfied          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    -- Set while the token waits for an external trigger.
    waits_for               TEXT        CHECK (waits_for IN ('TIMER', 'MESSAGE', 'SIGNAL',
                                                              'CONDITIONAL', 'ERROR', 'ESCALATION',
                                                              'TERMINATE', 'LINK', 'HUMAN', 'EXTERNAL')),
    -- Criteria used to match an incoming event to this token.
    wait_key                TEXT,
    wait_payload            JSONB,
    locked_by               TEXT,
    locked_at               TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The gateway-join guard. A partial UNIQUE index on the live states is the
-- single most important invariant in the whole engine: it makes releasing a
-- join twice impossible even under concurrent inbound events.
CREATE UNIQUE INDEX ux_wf_token_live
    ON wf_token (instance_id, node_id, COALESCE(mi_index, -1))
    WHERE state IN ('ACTIVE', 'WAITING', 'BLOCKED');

CREATE INDEX ix_wf_token_dispatch
    ON wf_token (instance_id, state)
    WHERE state = 'ACTIVE';

-- Incoming message / signal / escalation matching.
CREATE INDEX ix_wf_token_wait_key
    ON wf_token (tenant_id, waits_for, wait_key)
    WHERE waits_for IS NOT NULL;

CREATE INDEX ix_wf_token_scope
    ON wf_token (scope_instance_id)
    WHERE scope_instance_id IS NOT NULL;

-- Suspend/resume targets every token of an instance.
CREATE INDEX ix_wf_token_suspended
    ON wf_token (instance_id)
    WHERE state = 'SUSPENDED';

-- ---------------------------------------------------------------------------
-- Process variables, one row per (instance, scope, name).
-- ---------------------------------------------------------------------------
CREATE TABLE wf_variable (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    instance_id     BIGINT      NOT NULL REFERENCES wf_instance (id) ON DELETE CASCADE,
    -- NULL = process scope, otherwise the sub-process / call-activity scope.
    scope_id        TEXT,
    name            TEXT        NOT NULL,
    -- STRING | BOOLEAN | INTEGER | LONG | DOUBLE | DECIMAL | LOCAL_DATE
    -- | INSTANT | JSON | BINARY
    value_type      TEXT        NOT NULL,
    value_text      TEXT,
    value_bool      BOOLEAN,
    value_num       NUMERIC,
    value_json      JSONB,
    value_bytes     BYTEA,
    is_pii          BOOLEAN     NOT NULL DEFAULT FALSE,
    lock_version    BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wf_variable_scope_name UNIQUE (instance_id, scope_id, name),
    -- Exactly one value column is populated.
    CONSTRAINT ck_wf_variable_single_value CHECK (
        num_nonnulls(value_text, value_bool, value_num, value_json, value_bytes) = 1
    )
);

CREATE INDEX ix_wf_variable_name ON wf_variable (instance_id, name);
CREATE INDEX ix_wf_variable_pii ON wf_variable (instance_id) WHERE is_pii;
