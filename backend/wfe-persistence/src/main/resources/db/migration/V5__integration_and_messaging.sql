-- ===========================================================================
--  V5 - Integration adapters and transactional messaging
--
--  Secret material is never inlined: `auth_ref` / `secret_ref` point at the
--  platform secret store, so a credential rotation is an audit event rather
--  than a migration of every workflow that uses the connection.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- Connection registry (REST base URLs, WSDL endpoints, Kafka bootstrap, ...)
-- ---------------------------------------------------------------------------
CREATE TABLE wf_connection (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    code            TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    kind            TEXT        NOT NULL
                                CHECK (kind IN ('REST', 'SOAP', 'KAFKA', 'RABBITMQ', 'JMS',
                                                'DATABASE', 'EMAIL', 'SFTP')),
    auth_ref        TEXT,
    secret_ref      TEXT,
    base_url        TEXT,
    -- Non-secret settings: SASL mechanism, exchange, QOS, etc.
    properties      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    -- Trust-material fingerprint, so a CA swap is auditable.
    tls_fingerprint TEXT,
    created_by      BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ck_wf_connection_code_unique UNIQUE (tenant_id, code)
);

-- ---------------------------------------------------------------------------
-- Schema registry. Backs the v1.5 WSDL/XSD/OpenAPI-derived form builder; in
-- v1 it is only used to snapshot fingerprints at publish time.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_schema (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   TEXT        NOT NULL DEFAULT 'default',
    code        TEXT        NOT NULL,
    name        TEXT        NOT NULL,
    schema_kind TEXT        NOT NULL
                            CHECK (schema_kind IN ('WSDL', 'OPENAPI', 'XSD', 'JSONSCHEMA')),
    source_uri  TEXT,
    content     TEXT        NOT NULL,
    checksum    CHAR(64)    NOT NULL,
    version     INTEGER     NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_wf_schema_code_version_unique UNIQUE (tenant_id, code, version)
);

-- ---------------------------------------------------------------------------
-- Outbound call audit. This is what powers the "inspect this service call"
-- screen and the per-node latency/error panels on the dashboard.
-- Bodies are redacted at write time; a node marked sensitive stores nothing.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_service_call (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        TEXT        NOT NULL DEFAULT 'default',
    instance_id      BIGINT      REFERENCES wf_instance (id) ON DELETE CASCADE,
    node_id          TEXT,
    call_type        TEXT        NOT NULL
                                 CHECK (call_type IN ('REST', 'SOAP', 'KAFKA', 'RABBITMQ', 'JMS',
                                                      'SCRIPT', 'RULE')),
    connection_code  TEXT,
    direction        TEXT        NOT NULL DEFAULT 'OUTBOUND'
                                 CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    attempt          INTEGER     NOT NULL DEFAULT 1,
    -- Sent to the callee where the protocol allows it, so a retried call is
    -- not applied twice downstream.
    idempotency_key  TEXT,
    http_method      TEXT,
    url              TEXT,
    soap_action      TEXT,
    topic            TEXT,
    request_body     JSONB,
    response_body    JSONB,
    response_headers JSONB,
    -- Which mapping produced which variable, so the designer can show the
    -- provenance of a value.
    mappings         JSONB,
    status_code      INTEGER,
    outcome          TEXT        NOT NULL DEFAULT 'PENDING'
                                 CHECK (outcome IN ('PENDING', 'SUCCESS', 'CLIENT_ERROR',
                                                    'SERVER_ERROR', 'TIMEOUT', 'TRANSPORT_ERROR',
                                                    'SOAP_FAULT', 'MAPPING_ERROR', 'AUTH_ERROR')),
    error_detail     TEXT,
    duration_ms      INTEGER,
    job_id           BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX ix_wf_service_call_instance
    ON wf_service_call (instance_id, created_at DESC);

-- Reliability panel: failure rate per node / per endpoint.
CREATE INDEX ix_wf_service_call_outcome
    ON wf_service_call (tenant_id, outcome, created_at DESC);

-- A retried call must not be logged as a second logical call.
CREATE UNIQUE INDEX ux_wf_service_call_idempotency
    ON wf_service_call (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE wf_service_call
    ADD CONSTRAINT fk_wf_service_call_job
    FOREIGN KEY (job_id) REFERENCES wf_job (id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- Transactional messaging
--
--  Publish: the state change and the outbox row commit together; a relay then
--           publishes to the broker and marks the row SENT. A crash between
--           commit and publish is recovered by the relay, so no message is
--           lost and none is published twice.
--  Consume: the inbox row is keyed by the broker message id and written before
--           dispatch, so a redelivery after a crash cannot start a second
--           instance or fire a waiting token twice.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_outbox (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        TEXT        NOT NULL DEFAULT 'default',
    instance_id      BIGINT      REFERENCES wf_instance (id) ON DELETE CASCADE,
    channel          TEXT        NOT NULL,
    destination      TEXT        NOT NULL,
    message_key      TEXT,
    correlation_key  TEXT,
    headers          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    payload          JSONB       NOT NULL,
    -- True when a reply is expected, which parks the token in wf_pending_reply.
    expect_reply     BOOLEAN     NOT NULL DEFAULT FALSE,
    reply_timeout_ms INTEGER,
    status           TEXT        NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD')),
    attempts         INTEGER     NOT NULL DEFAULT 0,
    last_error       TEXT,
    sent_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_wf_outbox_relay
    ON wf_outbox (status, created_at)
    WHERE status = 'PENDING';

-- Tokens parked awaiting a reply, matched by correlation key.
CREATE TABLE wf_pending_reply (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    instance_id     BIGINT      NOT NULL REFERENCES wf_instance (id) ON DELETE CASCADE,
    token_id        BIGINT      REFERENCES wf_token (id) ON DELETE CASCADE,
    node_id         TEXT        NOT NULL,
    channel         TEXT        NOT NULL,
    correlation_key TEXT        NOT NULL,
    timeout_at      TIMESTAMPTZ,
    reply_job_id    BIGINT      REFERENCES wf_job (id) ON DELETE SET NULL,
    status          TEXT        NOT NULL DEFAULT 'WAITING'
                                CHECK (status IN ('WAITING', 'REPLIED', 'TIMED_OUT', 'CANCELLED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_wf_pending_reply_lookup
    ON wf_pending_reply (tenant_id, correlation_key)
    WHERE status = 'WAITING';

ALTER TABLE wf_pending_reply
    ADD CONSTRAINT fk_wf_pending_reply_job
    FOREIGN KEY (reply_job_id) REFERENCES wf_job (id) ON DELETE SET NULL;

CREATE TABLE wf_inbox (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    channel         TEXT        NOT NULL,
    message_id      TEXT        NOT NULL,
    correlation_key TEXT,
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'NEW'
                                CHECK (status IN ('NEW', 'PROCESSED', 'IGNORED', 'FAILED')),
    error_detail    TEXT,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT ck_wf_inbox_message_unique UNIQUE (tenant_id, channel, message_id)
);

CREATE INDEX ix_wf_inbox_unprocessed
    ON wf_inbox (received_at)
    WHERE status = 'NEW';
