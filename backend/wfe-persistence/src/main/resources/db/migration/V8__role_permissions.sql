-- ===========================================================================
--  V8 - Permission grants for the functional roles
--
--  V7 seeded the permission catalog and grants only ADMIN (everything) and
--  VIEWER (read-only). This completes the matrix for the roles a process is
--  actually executed under. admin:* stays ADMIN-only by construction:
--  connections, identity and audit are never reachable from a workflow role.
-- ===========================================================================

INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN (
    'definition:read', 'definition:write', 'definition:publish', 'definition:delete',
    'instance:read', 'task:read'
)
WHERE r.tenant_id = 'default'
  AND r.code = 'ROLE_DESIGNER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Operations runs instances and administers the tasks that belong to them.
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN (
    'definition:read', 'instance:read', 'instance:start', 'instance:control',
    'task:read', 'task:claim', 'task:complete', 'task:delegate'
)
WHERE r.tenant_id = 'default'
  AND r.code = 'ROLE_OPERATIONS'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Reviewer is the candidate group for review user tasks: it claims and
-- completes them, and can see what it is working on, but starts nothing.
INSERT INTO iam_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM iam_role r
JOIN iam_permission p ON p.code IN (
    'definition:read', 'instance:read', 'task:read', 'task:claim', 'task:complete',
    'task:delegate'
)
WHERE r.tenant_id = 'default'
  AND r.code = 'ROLE_REVIEWER'
ON CONFLICT (role_id, permission_id) DO NOTHING;