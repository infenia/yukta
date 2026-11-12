-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The whole schema. The app is alpha: the migrations were squashed into this one, so an existing
-- development database must be recreated.

-- Workflow definitions, their saved versions, session configuration and the execution log.

CREATE TABLE workflow_definition (
    session_id  TEXT        NOT NULL,
    workflow_id TEXT        NOT NULL,
    definition  JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, workflow_id)
);

CREATE TABLE workflow_definition_revision (
    session_id  TEXT        NOT NULL,
    workflow_id TEXT        NOT NULL,
    revision_id TEXT        NOT NULL,
    definition  JSONB       NOT NULL,
    saved_at    TIMESTAMPTZ NOT NULL,
    note        TEXT,
    PRIMARY KEY (session_id, workflow_id, revision_id)
);

CREATE TABLE session_config (
    session_id     TEXT  PRIMARY KEY,
    project_path   TEXT  NOT NULL DEFAULT '',
    initiator      TEXT  NOT NULL DEFAULT '',
    initiated_time TEXT  NOT NULL DEFAULT '',
    description    TEXT  NOT NULL DEFAULT '',
    name           TEXT  NOT NULL DEFAULT '',
    tags           JSONB NOT NULL DEFAULT '{}'
);

CREATE TABLE execution_log (
    seq          BIGSERIAL PRIMARY KEY,
    execution_id TEXT  NOT NULL,
    event        JSONB NOT NULL
);

CREATE INDEX execution_log_execution_id ON execution_log (execution_id);

-- What each node of a run produced (one row per message, oldest first by seq) and whether the
-- node ended normally with a complete output. Large messages are stored out of line by PostgreSQL
-- (TOAST), so no size cap is needed here.
CREATE TABLE node_checkpoint (
    seq          BIGSERIAL PRIMARY KEY,
    execution_id TEXT  NOT NULL,
    node_id      TEXT  NOT NULL,
    message      JSONB NOT NULL
);

CREATE INDEX node_checkpoint_node ON node_checkpoint (execution_id, node_id, seq);

CREATE TABLE node_checkpoint_state (
    execution_id TEXT    NOT NULL,
    node_id      TEXT    NOT NULL,
    done         BOOLEAN NOT NULL DEFAULT FALSE,
    incomplete   BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (execution_id, node_id)
);

-- Which server runs workflows when several share the database: the holder renews the lease
-- before it expires, another server takes it over once it has. The fencing number grows with every
-- change of holder.
CREATE TABLE leader_lease (
    name       TEXT        PRIMARY KEY,
    holder     TEXT        NOT NULL,
    fencing    BIGINT      NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

-- The state of KV_STORE nodes when yukta.kv.store-type=postgres.
CREATE TABLE kv_entry (
    k TEXT  PRIMARY KEY,
    v JSONB NOT NULL
);

-- The chat history of SESSION_MEMORY nodes when yukta.session-memory.store-type=postgres.
CREATE TABLE session_history (
    session_id TEXT        PRIMARY KEY,
    turns      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Supervisors and the attachments that give them a scope at run time. Ids follow
-- [A-Za-z0-9._-]{1,64}. Settings may hold a secret (a model API key); they are stored as given.
-- A supervisor may report to several parents and delegate to them: the ordered parent ids, and how
-- each kind of delegation (APPROVAL, INPUT, CONSULT) is asked, as JSON.
CREATE TABLE supervisor (
    id          TEXT        PRIMARY KEY,
    type        TEXT        NOT NULL,
    settings    JSONB       NOT NULL,
    dry_run     BOOLEAN     NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    description TEXT,
    parents     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    delegation  JSONB       NOT NULL DEFAULT '{}'::jsonb
);

-- An attachment has a source (MANUAL, DEFINITION or SYSTEM) and, for a DEFINITION one, the session
-- and workflow that declared it. One that owned a unit another attachment owns is disabled at
-- start-up and marked `conflict`. A `locked` one changes or goes only when the request asks to
-- unlock, by a key in yukta.supervisor.lock-holders when that is set.
CREATE TABLE supervisor_attachment (
    id                  TEXT        PRIMARY KEY,
    supervisor_id       TEXT        NOT NULL REFERENCES supervisor (id),
    scope               TEXT        NOT NULL,
    enabled             BOOLEAN     NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    source              TEXT        NOT NULL DEFAULT 'MANUAL'
        CHECK (source IN ('MANUAL', 'DEFINITION', 'SYSTEM')),
    origin_session_id   TEXT,
    origin_workflow_id  TEXT,
    conflict            BOOLEAN     NOT NULL DEFAULT false,
    locked              BOOLEAN     NOT NULL DEFAULT false
);

CREATE INDEX supervisor_attachment_supervisor ON supervisor_attachment (supervisor_id);
CREATE INDEX supervisor_attachment_origin ON supervisor_attachment (origin_session_id);

-- The supervisors a session declares for itself as a whole (the workflow ones are part of the
-- workflow definition). Attachments made from them have the source DEFINITION.
CREATE TABLE session_supervisor (
    session_id TEXT        PRIMARY KEY,
    refs       JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The newest saved states of each supervisor (rollback, the rules editor's history). No foreign
-- key: SupervisorService forgets the versions when it deletes the supervisor.
CREATE TABLE supervisor_version (
    supervisor_id TEXT        NOT NULL,
    version       INTEGER     NOT NULL,
    saved_at      TIMESTAMPTZ NOT NULL,
    body          JSONB       NOT NULL,
    PRIMARY KEY (supervisor_id, version)
);

-- The credential an application uses on the agent API, one per supervisor. Only the SHA-256 of the
-- token is kept, never the token. `allowed_addresses` are the source addresses it may be used
-- from, comma-separated; empty for any.
CREATE TABLE agent_credential (
    supervisor_id     TEXT        PRIMARY KEY,
    token_hash        TEXT        NOT NULL,
    expires_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    allowed_addresses TEXT        NOT NULL DEFAULT ''
);

-- Input requests (a node asks for a decision), their answers and the queue of deliveries to
-- external responders, so an open request, its deadlines and its queued deliveries survive a restart.
-- The spec is stored already redacted, with the prompt and the context. `asked` is the way a request
-- came to its current tier (the supervisors that asked for approval, the parents asked before a
-- person, each hand-up), so the inbox can show the chain after a restart.
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
    updated_at    TIMESTAMPTZ NOT NULL,
    asked         JSONB       NOT NULL DEFAULT '[]'::jsonb
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
