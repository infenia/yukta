// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * Rebuilds the status events of a past run from its execution log, so a draft supervisor can be
 * tried on them. Only statuses are rebuilt: node outputs and log lines were not kept.
 */
@UtilityClass
public class ReplayEvents {

  /**
   * The events of one run, in the order the log has them.
   *
   * @param log the log entries of the run
   * @return the status events
   */
  public static List<ExecutionEvent> from(final List<ExecutionLogEvent> log) {
    final List<ExecutionEvent> events = new ArrayList<>();
    for (final ExecutionLogEvent entry : log) {
      switch (entry.type()) {
        case RUN_STARTED -> events.add(workflow(entry, "RUNNING"));
        case NODE_FINISHED ->
            events.add(
                node(entry, entry.nodeId(), entry.status() == null ? "SUCCESS" : entry.status()));
        case RUN_FINISHED, RUN_ABORTED -> {
          for (final Map.Entry<String, String> node : entry.nodeStatuses().entrySet()) {
            events.add(node(entry, node.getKey(), node.getValue()));
          }
          events.add(workflow(entry, entry.status() == null ? "FAILURE" : entry.status()));
        }
        default -> {
          // control entries and bookkeeping are not events of the run
        }
      }
    }
    return events;
  }

  private static ExecutionEvent workflow(final ExecutionLogEvent entry, final String status) {
    return new ExecutionEvent.WorkflowStatusChanged(
        entry.executionId(), entry.sessionId(), entry.workflowId(), status, entry.timestamp());
  }

  private static ExecutionEvent node(
      final ExecutionLogEvent entry, final String nodeId, final String status) {
    return new ExecutionEvent.NodeStatusChanged(
        entry.executionId(),
        entry.sessionId(),
        entry.workflowId(),
        nodeId,
        "",
        status,
        Map.of(),
        entry.timestamp());
  }
}
