-- ===========================================================================
--  V6 - History, audit, business calendars and simulation
--
--  `wf_event_log` and `wf_audit_log` are strictly append-only. No UPDATE, no
--  DELETE, no cascade from a parent table. Retention is enforced by a
--  scheduled archival job, never by an application delete.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Execution history. Camunda-style: one row per state transition. This is the
-- table the BPMN conformance suite asserts against and the source of the
-- instance timeline and every dashboard metric.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_event_log (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         TEXT        NOT NULL DEFAULT 'default',
    instance_id       BIGINT      NOT NULL,
    scope_instance_id BIGINT,
    token_id          BIGINT,
    -- Gap-free per instance; allocated from wf_instance.history_seq.
    sequence_no       BIGINT      NOT NULL,
    node_id           TEXT,
    node_name         TEXT,
    node_type         TEXT,
    event_type        TEXT        NOT NULL
                                  CHECK (event_type IN (
                                      'INSTANCE_START', 'INSTANCE_END', 'INSTANCE_SUSPENDED',
                                      'INSTANCE_RESUMED', 'INSTANCE_CANCELLED',
                                      'ACTIVITY_STARTED', 'ACTIVITY_ENDED', 'ACTIVITY_CANCELLED',
                                      'TRANSITION_TAKEN', 'GATEWAY_EVALUATED',
                                      'VARIABLE_CREATED', 'VARIABLE_UPDATED', 'VARIABLE_DELETED',
                                      'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_CLAIMED',
                                      'TASK_COMPLETED', 'TASK_DELEGATED', 'TASK_ESCALATED',
                                      'EVENT_TRIGGERED', 'TIMER_CREATED', 'TIMER_FIRED',
                                      'MESSAGE_SENT', 'MESSAGE_RECEIVED',
                                      'SERVICE_CALLED', 'SERVICE_FAILED',
                                      'JOB_EXECUTED', 'JOB_FAILED',
                                      'INCIDENT_RAISED', 'INCIDENT_HANDLED',
                                      'COMPENSATION_TRIGGERED', 'LINK_CONSUMED',
                                      'MI_INSTANCE_CREATED', 'MI_INSTANCE_COMPLETED',
                                      'SUBPROCESS_STARTED', 'SUBPROCESS_ENDED',
                                      'CALL_ACTIVITY_STARTED', 'CALL_ACTIVITY_ENDED')),
    -- Old/new value for variable and state transitions; NULL variable values
    -- are redacted here when is_pii.
    detail            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Full history retention is the compliance requirement, so this table is
    -- detached from wf_instance's cascade and never hard-deleted.
    occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Timeline read for one instance.
CREATE INDEX ix_wf_event_log_instance
    ON wf_event_log (instance_id, sequence_no);

-- Dashboard aggregations: "activities completed per day", "cycle time".
CREATE INDEX ix_wf_event_log_type_time
    ON wf_event_log (tenant_id, event_type, occurred_at);

-- Per-node duration: the bottleneck view.
CREATE INDEX ix_wf_event_log_node
    ON wf_event_log (instance_id, node_id, event_type, occurred_at);

CREATE UNIQUE INDEX ux_wf_event_log_sequence
    ON wf_event_log (instance_id, sequence_no);

-- ---------------------------------------------------------------------------
-- Audit log: who changed what, independent of process execution.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     TEXT        NOT NULL DEFAULT 'default',
    actor_id      BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    actor_subject TEXT,
    action        TEXT        NOT NULL,
    resource_type TEXT        NOT NULL,
    resource_id   TEXT,
    -- Before/after for definition edits, or the payload for actions without one.
    before_state  JSONB,
    after_state   JSONB,
    -- Request correlation id, so an action can be tied to an HTTP request.
    correlation_id TEXT,
    client_ip     INET,
    user_agent    TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_wf_audit_log_resource
    ON wf_audit_log (tenant_id, resource_type, resource_id, occurred_at DESC);

CREATE INDEX ix_wf_audit_log_actor
    ON wf_audit_log (tenant_id, actor_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Business calendars, so a timer defined as "2 working days" means two working
-- days rather than 172800 seconds. Referenced by TimerEventDefinitions.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_business_calendar (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    TEXT        NOT NULL DEFAULT 'default',
    code         TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    timezone     TEXT        NOT NULL DEFAULT 'UTC',
    working_hours JSONB      NOT NULL
                             -- {"mon":[{"from":"09:00","to":"17:00"}], ...}
                             DEFAULT '{}'::jsonb,
    holidays     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT ck_wf_business_calendar_code_unique UNIQUE (tenant_id, code)
);

-- ---------------------------------------------------------------------------
-- Simulation: the designer's dry-run harness.
--
--  `wf_simulation_run` holds the scenario (input variables + mocked service
--  responses + the task decisions an operator would make). `wf_simulation_step`
--  is the token trace: one row per engine step, which is what the UI replays.
--  Neither table ever references wf_instance - a simulation must not be able to
--  touch live data by construction.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_simulation_run (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            TEXT        NOT NULL DEFAULT 'default',
    version_id           BIGINT      NOT NULL REFERENCES wf_version (id) ON DELETE CASCADE,
    name                 TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'PENDING'
                                       CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED',
                                                         'FAILED', 'ABORTED', 'TIMED_OUT')),
    -- Starting variables, then the final variable map at the end of the run.
    input_variables      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    final_variables      JSONB,
    -- Per-service-node mocked responses and per-user-task canned answers.
    mocks                JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Fixed seed so a scenario is reproducible across runs and environments.
    seed                 BIGINT      NOT NULL DEFAULT 0,
    -- Hard cap on steps; a run that hits it is TIMED_OUT, not left hanging.
    max_steps            INTEGER     NOT NULL DEFAULT 5000,
    steps_executed       INTEGER     NOT NULL DEFAULT 0,
    result_path          JSONB,
    error_detail         TEXT,
    duration_ms          INTEGER,
    created_by           BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ
);

CREATE INDEX ix_wf_simulation_run_version
    ON wf_simulation_run (version_id, created_at DESC);

CREATE TABLE wf_simulation_step (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    run_id          BIGINT      NOT NULL REFERENCES wf_simulation_run (id) ON DELETE CASCADE,
    step_no         INTEGER     NOT NULL,
    -- Stable per-branch token label, so the trace can draw converging paths.
    token_path      TEXT        NOT NULL,
    node_id         TEXT        NOT NULL,
    node_name       TEXT,
    node_type       TEXT,
    action          TEXT        NOT NULL
                                CHECK (action IN ('ENTER', 'EVALUATE', 'TAKE_TRANSITION', 'SPLIT',
                                                  'JOIN', 'WAIT', 'RESUME', 'MOCK', 'SKIP',
                                                  'TERMINATE', 'ABORT')),
    -- For EVALUATE/TAKE_TRANSITION: the flow and the expression + result.
    flow_id         TEXT,
    expression      TEXT,
    result          JSONB,
    -- Variable diff produced by this step, for the "what changed here" panel.
    variable_delta  JSONB,
    message         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_wf_simulation_step_no UNIQUE (run_id, step_no)
);

CREATE INDEX ix_wf_simulation_step_run
    ON wf_simulation_step (run_id, step_no);
