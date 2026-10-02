-- ===========================================================================
--  V7 - Reference data (production-safe)
--
--  Only rows that must exist in every environment. Local/dev users live in the
--  separate, non-packaged `db/seed` location.
-- ===========================================================================

INSERT INTO iam_permission (tenant_id, code, description) VALUES
    ('default', 'definition:read',    'View workflow definitions and diagrams'),
    ('default', 'definition:write',   'Create and edit drafts'),
    ('default', 'definition:publish', 'Validate and publish a version'),
    ('default', 'definition:delete',  'Archive a definition'),
    ('default', 'instance:read',      'View process instances'),
    ('default', 'instance:start',     'Start a process instance'),
    ('default', 'instance:control',   'Suspend, resume, cancel or signal an instance'),
    ('default', 'task:read',          'View human tasks in queues and instances'),
    ('default', 'task:claim',         'Claim a task from a candidate queue'),
    ('default', 'task:complete',      'Complete a claimed task'),
    ('default', 'task:delegate',      'Delegate or transfer a task'),
    ('default', 'admin:connection',   'Manage integration connections'),
    ('default', 'admin:schema',       'Manage the WSDL / OpenAPI schema registry'),
    ('default', 'admin:identity',     'Manage users, roles and grants'),
    ('default', 'admin:audit',        'View the audit log');

-- System roles. `assignable = false` because a user task may not target an
-- administrative role; the validator rejects such a candidateGroup.
INSERT INTO iam_role (tenant_id, code, name, description, assignable) VALUES
    ('default', 'ROLE_DESIGNER',    'Workflow Designer',
     'May author, validate, publish and simulate workflow definitions.', TRUE),
    ('default', 'ROLE_VIEWER',      'Viewer',
     'Read-only access to definitions, instances and dashboards.', TRUE),
    ('default', 'ROLE_OPERATIONS',  'Operations',
     'Starts instances and administers running ones.', TRUE),
    ('default', 'ROLE_REVIEWER',    'Reviewer',
     'Intended as a user-task candidate group for review steps.', TRUE),
    ('default', 'ROLE_ADMIN',       'Administrator',
     'Full access including identity, connections and audit.', FALSE);

-- Admin gets everything, by construction rather than by ad-hoc grants.
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
CROSS JOIN iam_permission p
WHERE r.tenant_id = 'default'
  AND r.code = 'ROLE_ADMIN';

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN (
    'definition:read', 'instance:read', 'task:read'
)
WHERE r.tenant_id = 'default'
  AND r.code = 'ROLE_VIEWER';
