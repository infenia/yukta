-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- An attachment that owned a unit another attachment owns is disabled at start-up and marked, so the
-- administrator sees it and resolves it. One owner per unit: see design/plans/supervisor-framework.md.
ALTER TABLE supervisor_attachment ADD COLUMN conflict BOOLEAN NOT NULL DEFAULT false;
