-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The newest saved states of each supervisor (rollback, the rules editor's history) and the hold
-- ledger (the runs a supervisor paused, for "resume held"), so both survive a restart
-- (design/plans/supervisor-framework.md, S4 and S5). No foreign key: a version outlives a rename by
-- adoption, and SupervisorService forgets the versions when it deletes the supervisor.
CREATE TABLE supervisor_version (
    supervisor_id TEXT        NOT NULL,
    version       INTEGER     NOT NULL,
    saved_at      TIMESTAMPTZ NOT NULL,
    body          JSONB       NOT NULL,
    PRIMARY KEY (supervisor_id, version)
);

-- holder is a supervisor or an attachment id
CREATE TABLE supervisor_hold (
    holder       TEXT        NOT NULL,
    execution_id TEXT        NOT NULL,
    held_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (holder, execution_id)
);
CREATE INDEX supervisor_hold_execution ON supervisor_hold (execution_id);
