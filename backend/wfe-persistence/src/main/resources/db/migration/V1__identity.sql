-- ===========================================================================
--  V1 - Identity & access
--
--  The product is single-tenant today, but `tenant_id` exists on every business
--  table: multi-tenancy later is then a WHERE clause rather than a data
--  migration. Identity splits cleanly in two:
--    * `external_id` is the OIDC subject claim - authentication concerns.
--    * `iam_role` / `iam_user_role` are the authorization authority. Per the
--      hybrid decision, roles are read from here and never trusted from the
--      token, so a revoked role takes effect on the next claim.
-- ===========================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- Users
-- ---------------------------------------------------------------------------
CREATE TABLE iam_user (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    -- Subject claim from the OIDC provider. NULL until the user first logs in,
    -- so the row can be provisioned by an administrator in advance.
    external_id     TEXT,
    username        TEXT        NOT NULL,
    email           TEXT,
    display_name    TEXT,
    locale          TEXT        NOT NULL DEFAULT 'en',
    timezone        TEXT        NOT NULL DEFAULT 'UTC',
    status          TEXT        NOT NULL DEFAULT 'ACTIVE'
                                CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    attributes      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ck_iam_user_username_unique UNIQUE (tenant_id, username)
);

-- Partial index: external_id is unique but must tolerate many NULLs.
CREATE UNIQUE INDEX ux_iam_user_external_id
    ON iam_user (tenant_id, external_id)
    WHERE external_id IS NOT NULL;

-- Case-insensitive username lookup, used when the token has no subject match
-- and we fall back to `preferred_username`.
CREATE INDEX ix_iam_user_username_lower
    ON iam_user (lower(username))
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------
CREATE TABLE iam_role (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    code            TEXT        NOT NULL,
    name            TEXT        NOT NULL,
    description     TEXT,
    -- Non-assignable roles only group other roles; a user task may not use
    -- them as a candidateGroup. Enforced in the validator.
    assignable      BOOLEAN     NOT NULL DEFAULT TRUE,
    attributes      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT ck_iam_role_code_unique UNIQUE (tenant_id, code)
);

-- ---------------------------------------------------------------------------
-- Grants, with an optional validity window
-- ---------------------------------------------------------------------------
CREATE TABLE iam_user_role (
    user_id         BIGINT      NOT NULL REFERENCES iam_user (id) ON DELETE CASCADE,
    role_id         BIGINT      NOT NULL REFERENCES iam_role (id) ON DELETE CASCADE,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    -- Temporary/acting grants are data, not code.
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to        TIMESTAMPTZ,
    granted_by      BIGINT      REFERENCES iam_user (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT ck_iam_user_role_window CHECK (valid_to IS NULL OR valid_to > valid_from)
);

-- Role -> members lookup, the query behind every candidate-role task inbox.
CREATE INDEX ix_iam_user_role_role
    ON iam_user_role (tenant_id, role_id, user_id);

-- Only currently-valid grants are ever returned; partial index keeps it small.
CREATE INDEX ix_iam_user_role_active
    ON iam_user_role (role_id, user_id)
    WHERE valid_to IS NULL;

-- ---------------------------------------------------------------------------
-- Authorisation entries used by @PreAuthorize expressions.
-- ---------------------------------------------------------------------------
CREATE TABLE iam_permission (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       TEXT        NOT NULL DEFAULT 'default',
    code            TEXT        NOT NULL,
    description     TEXT,
    CONSTRAINT ck_iam_permission_code_unique UNIQUE (tenant_id, code)
);

CREATE TABLE iam_role_permission (
    role_id         BIGINT      NOT NULL REFERENCES iam_role (id) ON DELETE CASCADE,
    permission_id   BIGINT      NOT NULL REFERENCES iam_permission (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
