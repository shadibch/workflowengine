-- ===========================================================================
--  Dev-only identity seed. NOT packaged into the application jar.
--
--  This location is wired in `application-local.yml` only:
--      spring.flyway.locations = classpath:db/migration,classpath:db/seed
--
--  Rows are matched to Keycloak logins by username (Keycloak assigns the OIDC
--  subject at realm import, so a deterministic external_id is impossible). On a
--  user's first request the authentication converter stamps the token's `sub`
--  onto the row, and all later requests match by subject. Roles are then read
--  from here at task-creation and claim time, which is the hybrid model agreed
--  for the project.
--
--  Idempotent: repeatable migration, re-applied on every local startup.
-- ===========================================================================

INSERT INTO iam_user (tenant_id, username, email, display_name, locale, timezone)
VALUES
    ('default', 'admin',      'admin@wfe.local',      'WFE Administrator', 'en', 'UTC'),
    ('default', 'designer',   'designer@wfe.local',   'Dana Designer',     'en', 'UTC'),
    ('default', 'ops',        'ops@wfe.local',        'Omar Operations',   'en', 'Asia/Dubai'),
    ('default', 'reviewer1',  'reviewer1@wfe.local',  'Rana Reviewer',     'ar', 'Asia/Dubai'),
    ('default', 'reviewer2',  'reviewer2@wfe.local',  'Sami Reviewer',     'ar', 'Asia/Dubai'),
    ('default', 'approver',   'approver@wfe.local',   'Aisha Approver',    'en', 'Europe/London'),
    ('default', 'viewer',     'viewer@wfe.local',     'Vera Viewer',       'en', 'UTC')
ON CONFLICT (tenant_id, username) DO UPDATE
SET email        = EXCLUDED.email,
    display_name = EXCLUDED.display_name,
    locale       = EXCLUDED.locale,
    timezone     = EXCLUDED.timezone,
    updated_at   = now();

-- Keycloak must be able to mint tokens for these users; the realm's client
-- role scope mirrors the same codes, but authorization decisions are always
-- re-resolved against these grants.
INSERT INTO iam_user_role (tenant_id, user_id, role_id)
SELECT 'default', u.id, r.id
FROM iam_user u
JOIN iam_role r ON r.code = CASE u.username
    WHEN 'admin'     THEN 'ROLE_ADMIN'
    WHEN 'designer'  THEN 'ROLE_DESIGNER'
    WHEN 'ops'       THEN 'ROLE_OPERATIONS'
    WHEN 'viewer'    THEN 'ROLE_VIEWER'
    WHEN 'approver'  THEN 'ROLE_REVIEWER'
    ELSE 'ROLE_REVIEWER'
END
WHERE u.tenant_id = 'default'
  AND u.username IN ('admin', 'designer', 'ops', 'reviewer1', 'reviewer2', 'approver', 'viewer')
ON CONFLICT (user_id, role_id) DO NOTHING;

-- The two reviewers exist to prove concurrent claiming of a shared
-- candidate-role queue: both are in ROLE_REVIEWER, neither is the assignee.
INSERT INTO iam_user_role (tenant_id, user_id, role_id)
SELECT 'default', u.id, r.id
FROM iam_user u, iam_role r
WHERE u.tenant_id = 'default' AND r.tenant_id = 'default'
  AND r.code = 'ROLE_REVIEWER'
  AND u.username IN ('reviewer1', 'reviewer2')
ON CONFLICT (user_id, role_id) DO NOTHING;
