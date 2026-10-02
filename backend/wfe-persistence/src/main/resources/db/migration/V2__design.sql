-- ===========================================================================
--  V2 - Definitions, versions and drafts
--
--  Design notes
--  ----------
--  * `wf_version.bpmn_xml` holds the byte-for-byte designer payload and is the
--    single source of truth for export. The engine never re-serialises its own
--    model back to BPMN, so import -> store -> export is lossless and third
--    party tools (Camunda, Flowable, jBPM) can read our files.
--  * `wf_version.graph` is the compiled execution graph produced by wfe-bpmn.
--    It is derived data and can always be rebuilt from the XML.
--  * `checksum` is a SHA-256 of the normalised XML, which turns "publish the
--    same design again" into a no-op instead of a duplicate version.
-- ===========================================================================

CREATE TABLE wf_definition (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                   TEXT        NOT NULL DEFAULT 'default',
    key                         TEXT        NOT NULL,
    name                        TEXT        NOT NULL,
    description                 TEXT,
    category                    TEXT,
    -- Version used when a caller starts an instance without naming one.
    latest_published_version_id BIGINT,
    status                      TEXT        NOT NULL DEFAULT 'DRAFT'
                                        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    -- Optimistic lock on the mutable draft row (autosave writes this).
    draft_revision              BIGINT      NOT NULL DEFAULT 0,
    created_by                  BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    updated_by                  BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                  TIMESTAMPTZ,
    CONSTRAINT ck_wf_definition_key_unique UNIQUE (tenant_id, key)
);

-- Definition picker: substring search on the business key.
CREATE INDEX ix_wf_definition_key_trgm
    ON wf_definition USING gin (key gin_trgm_ops);

CREATE INDEX ix_wf_definition_listing
    ON wf_definition (tenant_id, updated_at DESC)
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- Versions. A definition has exactly one mutable DRAFT row and any number of
-- frozen PUBLISHED / DEPRECATED rows.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_version (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    definition_id   BIGINT      NOT NULL REFERENCES wf_definition (id) ON DELETE CASCADE,
    version_no      INTEGER     NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'DRAFT'
                                CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    -- Verbatim BPMN 2.0 XML: definitions, lanes, DI layout, wfe: extensions.
    bpmn_xml        TEXT        NOT NULL,
    -- Compiled execution graph. NULL while a draft is still incomplete.
    graph           JSONB,
    checksum        CHAR(64)    NOT NULL,
    -- Output of the structural + semantic validator; drives designer markers.
    validation      JSONB,
    -- WSDL/OpenAPI fingerprints captured at publish time, so a later schema
    -- change is detected instead of silently corrupting a response mapping.
    schema_refs     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    notes           TEXT,
    created_by      BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    published_by    BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ck_wf_version_no_unique UNIQUE (definition_id, version_no),
    -- A version is either the mutable draft or a frozen published artefact.
    CONSTRAINT ck_wf_version_publish_state CHECK (
        (status = 'DRAFT'  AND published_at IS NULL  AND published_by IS NULL) OR
        (status <> 'DRAFT' AND published_at IS NOT NULL)
    )
);

CREATE INDEX ix_wf_version_definition
    ON wf_version (tenant_id, definition_id, version_no DESC);

-- Hot lookup: newest PUBLISHED version of a definition.
CREATE INDEX ix_wf_version_published
    ON wf_version (definition_id, version_no DESC)
    WHERE status = 'PUBLISHED' AND deleted_at IS NULL;

-- Republishing an identical design is a no-op.
CREATE UNIQUE INDEX ux_wf_version_checksum
    ON wf_version (definition_id, checksum)
    WHERE deleted_at IS NULL;

ALTER TABLE wf_definition
    ADD CONSTRAINT fk_wf_definition_latest_version
    FOREIGN KEY (latest_published_version_id) REFERENCES wf_version (id) ON DELETE SET NULL;

-- Per-definition version counter, incremented under row lock so concurrent
-- publishes cannot collide on version_no.
CREATE TABLE wf_version_counter (
    definition_id   BIGINT PRIMARY KEY REFERENCES wf_definition (id) ON DELETE CASCADE,
    last_version_no INTEGER NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- Task form definitions, authored in the designer as JSON Schema + layout.
-- Kept out of the BPMN XML so a form can be revised independently of a
-- published process version, and referenced from `wf_task.form_schema`.
-- ---------------------------------------------------------------------------
CREATE TABLE wf_form_definition (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    key             TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    -- JSON Schema (form) + {layout, bindings, visibility} (rendering).
    schema          JSONB       NOT NULL,
    layout          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version         INTEGER     NOT NULL DEFAULT 1,
    created_by      BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ck_wf_form_definition_key_version UNIQUE (tenant_id, key, version)
);
