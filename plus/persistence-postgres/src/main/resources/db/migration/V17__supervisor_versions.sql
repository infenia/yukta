-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The newest saved states of each supervisor (rollback, the rules editor's history), so they
-- survive a restart (design/plans/supervisor-framework.md, S5). No foreign key: a version
-- outlives a rename by adoption, and SupervisorService forgets the versions when it deletes the
-- supervisor.
CREATE TABLE supervisor_version (
    supervisor_id TEXT        NOT NULL,
    version       INTEGER     NOT NULL,
    saved_at      TIMESTAMPTZ NOT NULL,
    body          JSONB       NOT NULL,
    PRIMARY KEY (supervisor_id, version)
);
