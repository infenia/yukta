-- SPDX-FileCopyrightText: 2026 Infenia Private Limited
-- SPDX-License-Identifier: AGPL-3.0-only

-- Rows exactly as the stores wrote them (schema version 6, main on 2026-10-23). FixtureReadTest
-- loads every fixtures/*/rows.sql and reads the rows back through the current stores, so renaming
-- a field of a stored document fails the build. Add a directory per release; never edit one.
INSERT INTO session_config (session_id, description, tags) VALUES ('s1', 'kept', '{"team":"core"}');
INSERT INTO workflow_definition (session_id, workflow_id, definition) VALUES ('s1', 'w1', '{"edges": [], "nodes": [{"type": "CONSTANT_SOURCE", "config": {"k": "v"}, "nodeId": "n1"}], "workflowId": "w1", "description": "d"}');
INSERT INTO workflow_definition_revision (session_id, workflow_id, revision_id, definition, saved_at) VALUES ('s1', 'w1', 'r1', '{"edges": [], "nodes": [{"type": "CONSTANT_SOURCE", "config": {"k": "v"}, "nodeId": "n1"}], "workflowId": "w1", "description": "d"}', now());
INSERT INTO execution_log (execution_id, event) VALUES ('r1', '{"type": "RUN_STARTED", "nodeId": null, "status": null, "payload": {"k": "v"}, "sessionId": "s1", "timestamp": "2026-10-23T14:57:59.979799212Z", "workflowId": "w1", "executionId": "r1", "nodeStatuses": {}, "definitionVersion": "v"}');
INSERT INTO execution_log (execution_id, event) VALUES ('r1', '{"type": "RUN_FINISHED", "nodeId": null, "status": "SUCCESS", "payload": null, "sessionId": "s1", "timestamp": "2026-10-23T14:57:59.980717993Z", "workflowId": "w1", "executionId": "r1", "nodeStatuses": {}, "definitionVersion": null}');
INSERT INTO node_checkpoint (execution_id, node_id, message) VALUES ('r1', 'n1', '{"id": "2896613a-0de2-4500-82e1-022c21f0f1bc", "payload": {"k": 1}, "replyTo": null, "traceId": "8cf6ae72-499f-4060-9c8a-a237fc5a525b", "metadata": {}, "origDest": null, "priority": 0, "timestamp": "2026-10-23T14:58:00.073352553Z", "expiration": 0, "retryCount": 0, "sequenceId": null, "sourcePort": null, "workflowId": null, "sequenceSize": 0, "sourceNodeId": null, "correlationId": null, "failureReason": null, "controlMessage": false, "messageHistory": [], "sequenceNumber": 0, "exceptionDetail": null, "formatIndicator": null}');
INSERT INTO kv_entry (k, v) VALUES ('w1:count', '3');
INSERT INTO session_history (session_id, turns) VALUES ('s1', '[{"role": "user", "text": "hi"}]');
