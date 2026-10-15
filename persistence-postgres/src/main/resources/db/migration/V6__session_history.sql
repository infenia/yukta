-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: Apache-2.0

-- The chat history of SESSION_MEMORY nodes when yukta.session-memory.store-type=postgres.
CREATE TABLE session_history (
    session_id TEXT        PRIMARY KEY,
    turns      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
