-- SPDX-License-Identifier: AGPL-3.0-only
-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
--
-- Notifications: the inbox, what each person did with it, their preferences and the outbox to
-- outbound channels. Replaces input_delivery: a queued delivery of alpha.4 is not carried over, the
-- notification producer queues the delivery of an open request again (its repair at start-up).

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

DROP TABLE input_delivery;
