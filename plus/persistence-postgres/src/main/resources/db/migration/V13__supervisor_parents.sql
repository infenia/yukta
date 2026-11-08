-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- A supervisor may report to several parents and delegate to them: the ordered parent ids, and how
-- each kind of delegation (APPROVAL, INPUT, CONSULT) is asked, as JSON.
ALTER TABLE supervisor ADD COLUMN parents JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE supervisor ADD COLUMN delegation JSONB NOT NULL DEFAULT '{}'::jsonb;
