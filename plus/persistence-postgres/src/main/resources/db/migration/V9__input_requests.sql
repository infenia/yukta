-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- Input requests (a node asks for a decision), their answers and the queue of deliveries to
-- external responders, so an open request, its deadlines and its queued deliveries survive a restart.
-- The spec is stored already redacted, with the prompt and the context.
CREATE TABLE input_request (
    id            TEXT        PRIMARY KEY,
    req_key       TEXT        NOT NULL,
    session_id    TEXT        NOT NULL,
    workflow_id   TEXT        NOT NULL,
    execution_id  TEXT        NOT NULL,
    node_id       TEXT        NOT NULL,
    kind          TEXT        NOT NULL,
    risk          TEXT        NOT NULL,
    state         TEXT        NOT NULL,
    version       BIGINT      NOT NULL,
    tier          TEXT        NOT NULL,
    targets       JSONB       NOT NULL,
    tier_deadline TIMESTAMPTZ,
    deadline      TIMESTAMPTZ,
    spec          JSONB       NOT NULL,
    advice        TEXT,
    votes         JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);

-- One live request per key: a second ask with the same key finds the first.
CREATE UNIQUE INDEX input_request_open_key ON input_request (req_key)
    WHERE state IN ('OPEN', 'ESCALATED');
CREATE INDEX input_request_key ON input_request (req_key, updated_at DESC);
CREATE INDEX input_request_due ON input_request (state, deadline);
CREATE INDEX input_request_scope ON input_request (session_id, state);
CREATE INDEX input_request_exec ON input_request (execution_id);

-- No foreign key: the store keeps an answer whatever became of its request; the purge removes both.
CREATE TABLE input_response (
    request_id  TEXT        PRIMARY KEY,
    response    JSONB       NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE input_delivery (
    id           TEXT        PRIMARY KEY,
    request_id   TEXT        NOT NULL REFERENCES input_request (id) ON DELETE CASCADE,
    tier         TEXT        NOT NULL,
    responder    TEXT        NOT NULL,
    state        TEXT        NOT NULL,
    attempts     INT         NOT NULL,
    next_attempt TIMESTAMPTZ NOT NULL,
    last_error   TEXT
);

CREATE INDEX input_delivery_due ON input_delivery (state, next_attempt);
CREATE INDEX input_delivery_request ON input_delivery (request_id);
