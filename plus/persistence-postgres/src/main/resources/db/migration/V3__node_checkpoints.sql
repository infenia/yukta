-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

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
