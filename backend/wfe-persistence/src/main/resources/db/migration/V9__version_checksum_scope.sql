-- Scope the checksum uniqueness rule to published versions only.
--
-- V2 created ux_wf_version_checksum over every non-deleted row. That is wrong once
-- publishing opens a fresh draft seeded from the version it just published: the new
-- draft carries the same checksum as its parent, so the next publish fails on a
-- unique violation even though the designer did nothing wrong.
--
-- The rule the feature actually needs is narrower: two *published* versions of the
-- same definition may not be identical. Drafts are expected to duplicate their
-- parent -- that is what "continue from what you published" means.
DROP INDEX IF EXISTS ux_wf_version_checksum;

CREATE UNIQUE INDEX ux_wf_version_checksum
    ON wf_version (definition_id, checksum)
    WHERE status = 'PUBLISHED' AND deleted_at IS NULL;

COMMENT ON INDEX ux_wf_version_checksum IS
    'Republishing an identical design is a no-op; drafts are excluded because a new draft is seeded from the version just published';
