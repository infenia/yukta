-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The way an input request came to its current tier (the supervisors that asked for approval, the
-- parents asked before a person, each hand-up), so the inbox can show the chain after a restart
-- (design/plans/supervisor-framework.md, S8d).
ALTER TABLE input_request ADD COLUMN asked JSONB NOT NULL DEFAULT '[]'::jsonb;
