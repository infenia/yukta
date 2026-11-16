-- SPDX-License-Identifier: AGPL-3.0-only
-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
--
-- Conversations with AI agents (AGENT_SESSION nodes) and what happened in them. Terminal output is
-- not stored (the store keeps a ring of it in memory); the last settled screen is.

-- The change sequence: every conversation save and every event takes the next value.
CREATE SEQUENCE agent_seq;

-- One row, written once: names this database for the stream's event ids, so a restart keeps them.
CREATE TABLE agent_epoch (
    id    INT  PRIMARY KEY CHECK (id = 1),
    epoch TEXT NOT NULL
);
INSERT INTO agent_epoch (id, epoch) VALUES (1, gen_random_uuid()::text);

CREATE TABLE agent_conversation (
    id                TEXT        PRIMARY KEY,
    execution_id      TEXT        NOT NULL,
    root_execution_id TEXT        NOT NULL,
    session_id        TEXT        NOT NULL,
    workflow_id       TEXT        NOT NULL,
    node_id           TEXT        NOT NULL,
    message_id        TEXT        NOT NULL,
    harness           TEXT        NOT NULL,
    protocol          TEXT        NOT NULL,
    model             TEXT,
    capabilities      JSONB       NOT NULL,
    state             TEXT        NOT NULL,
    outcome           TEXT,
    reason            TEXT,
    turn              INT         NOT NULL,
    queued            INT         NOT NULL,
    steer_audience    JSONB       NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    ended_at          TIMESTAMPTZ,
    seq               BIGINT      NOT NULL,
    -- true once the oldest events were dropped and the notice that says so was written
    dropped           BOOLEAN     NOT NULL DEFAULT FALSE,
    -- the latest settled screen of a terminal, as the event that carried it
    screen            JSONB
);

CREATE INDEX agent_conversation_started ON agent_conversation (started_at);
CREATE INDEX agent_conversation_run ON agent_conversation (root_execution_id);
CREATE INDEX agent_conversation_session ON agent_conversation (session_id);
CREATE INDEX agent_conversation_purge ON agent_conversation (ended_at);

CREATE TABLE agent_event (
    seq             BIGINT      PRIMARY KEY,
    conversation_id TEXT        NOT NULL REFERENCES agent_conversation (id) ON DELETE CASCADE,
    occurred_at     TIMESTAMPTZ NOT NULL,
    kind            TEXT        NOT NULL,
    item_id         TEXT,
    data            JSONB       NOT NULL
);

CREATE INDEX agent_event_conversation ON agent_event (conversation_id, seq);
