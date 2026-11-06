-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The supervisors a session declares for itself as a whole (the workflow ones are part of the
-- workflow definition). Attachments made from them have the source DEFINITION.
CREATE TABLE session_supervisor (
    session_id TEXT        PRIMARY KEY,
    refs       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
