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
