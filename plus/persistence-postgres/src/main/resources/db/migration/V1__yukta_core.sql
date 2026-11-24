-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- The whole schema. The app is alpha: the migrations were squashed into this one again
-- (2026-11-24, with V2 to V5), so an existing database must be recreated.

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

-- One row per event. `type` and `ts` repeat the event's type and timestamp as columns, so the
-- queries by type and time use an index instead of reading the JSON.
CREATE TABLE execution_log (
    seq          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id TEXT        NOT NULL,
    type         TEXT        NOT NULL,
    ts           TIMESTAMPTZ NOT NULL,
    event        JSONB       NOT NULL
);

-- The events of one run in order (forExecution, newest of one run).
CREATE INDEX execution_log_run ON execution_log (execution_id, seq);
-- The events of one type in order (ofType, newest across runs).
CREATE INDEX execution_log_type ON execution_log (type, seq);
-- The starts and ends of runs, about two rows per run: the log trim reads these instead of the
-- whole log. Queries must repeat the predicate (or a subset of it) to use the index.
CREATE INDEX execution_log_run_edge ON execution_log (execution_id, seq)
    WHERE type IN ('RUN_STARTED', 'RUN_FINISHED', 'RUN_ABORTED');

-- What each node of a run produced (one row per message, oldest first by seq) and whether the
-- node ended normally with a complete output. Large messages are stored out of line by PostgreSQL
-- (TOAST), so no size cap is needed here.
CREATE TABLE node_checkpoint (
    seq          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execution_id TEXT   NOT NULL,
    node_id      TEXT   NOT NULL,
    message      JSONB  NOT NULL
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
-- and workflow that declared it. A `locked` one changes or goes only when the request asks to
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

-- Input requests (a node asks for a decision) and their answers, so an open request and its
-- deadlines survive a restart. Deliveries to outbound channels are notification_deliveries.
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
-- The newest answer for a key (findAnsweredByKey).
CREATE INDEX input_request_answered ON input_request (req_key, updated_at DESC)
    WHERE state = 'ANSWERED';
-- Live requests past a deadline (dueBefore asks LEAST(deadline, tier_deadline) <= now; LEAST skips
-- a null deadline).
CREATE INDEX input_request_due ON input_request (LEAST(deadline, tier_deadline))
    WHERE state IN ('OPEN', 'ESCALATED');
-- Ended requests older than the retention (purgeTerminalOlderThan).
CREATE INDEX input_request_purge ON input_request (updated_at)
    WHERE NOT (state IN ('OPEN', 'ESCALATED'));
CREATE INDEX input_request_scope ON input_request (session_id, state);
CREATE INDEX input_request_exec ON input_request (execution_id);

-- No foreign key: the store keeps an answer whatever became of its request; the purge removes both.
CREATE TABLE input_response (
    request_id  TEXT        PRIMARY KEY,
    response    JSONB       NOT NULL,
    answered_at TIMESTAMPTZ NOT NULL
);

-- Notifications: the inbox, what each person did with it, their preferences and the outbox to
-- outbound channels.

-- The change sequence of the notifications: every create, update and resolve takes the next value.
CREATE SEQUENCE notification_seq;

-- One row, written once: names this database for the stream's event ids, so a restart keeps them.
CREATE TABLE notification_epoch (
    id    INT  PRIMARY KEY CHECK (id = 1),
    epoch TEXT NOT NULL
);
INSERT INTO notification_epoch (id, epoch) VALUES (1, gen_random_uuid()::text);

CREATE TABLE notifications (
    id               TEXT        PRIMARY KEY,
    notif_key        TEXT        NOT NULL UNIQUE,
    kind             TEXT        NOT NULL,
    severity         TEXT        NOT NULL,
    category         TEXT        NOT NULL,
    title            TEXT        NOT NULL,
    body             TEXT,
    session_id       TEXT,
    workflow_id      TEXT,
    execution_id     TEXT,
    node_id          TEXT,
    input_request_id TEXT,
    audience         JSONB       NOT NULL,
    state            TEXT        NOT NULL,
    resolution       TEXT,
    count            INT         NOT NULL,
    seq              BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX notifications_seq ON notifications (seq);
CREATE INDEX notifications_purge ON notifications (updated_at);

CREATE TABLE notification_receipts (
    notification_id TEXT        NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    recipient       TEXT        NOT NULL,
    read_at         TIMESTAMPTZ,
    dismissed_at    TIMESTAMPTZ,
    PRIMARY KEY (recipient, notification_id)
);

CREATE INDEX notification_receipt_notification ON notification_receipts (notification_id);

CREATE TABLE notification_preferences (
    recipient      TEXT  PRIMARY KEY,
    levels         JSONB NOT NULL,
    muted_sessions JSONB NOT NULL
);

CREATE TABLE notification_deliveries (
    id              TEXT        PRIMARY KEY,
    notification_id TEXT        NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    channel         TEXT        NOT NULL,
    tier            TEXT,
    state           TEXT        NOT NULL,
    attempts        INT         NOT NULL,
    next_attempt    TIMESTAMPTZ NOT NULL,
    last_error      TEXT
);

CREATE INDEX notification_delivery_due ON notification_deliveries (state, next_attempt);
CREATE INDEX notification_delivery_notification ON notification_deliveries (notification_id);

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
