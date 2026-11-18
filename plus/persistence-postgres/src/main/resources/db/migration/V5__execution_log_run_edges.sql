-- SPDX-License-Identifier: AGPL-3.0-only
-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
--
-- The starts and ends of runs, about two rows per run: the log trim reads these instead of grouping
-- the whole execution_log. Queries must repeat the predicate (or a subset of it) to use the index.
CREATE INDEX execution_log_run_edge ON execution_log (execution_id, seq)
    WHERE event ->> 'type' IN ('RUN_STARTED', 'RUN_FINISHED', 'RUN_ABORTED');
