-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The credential an application uses on the agent API, one per supervisor. Only the SHA-256 of the
-- token is kept, never the token.
CREATE TABLE agent_credential (
    supervisor_id TEXT PRIMARY KEY,
    token_hash    TEXT NOT NULL,
    expires_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
