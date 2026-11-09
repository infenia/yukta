-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- A locked assignment changes or goes only when the request asks to unlock, by a key in
-- yukta.supervisor.lock-holders when that is set (design/plans/supervisor-framework.md, S7).
ALTER TABLE supervisor_attachment ADD COLUMN locked BOOLEAN NOT NULL DEFAULT false;
