// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.SupervisorPreset;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Rule documents to start from. */
@UtilityClass
public class RulePresets {

  private static final String COMMAND = "command";
  private static final String REASON = "reason";
  private static final String PAUSE = "PauseWorkflow";

  /** The presets, each a complete rule document of version 1. */
  public static final List<SupervisorPreset> ALL =
      List.of(
          new SupervisorPreset(
              "failure-guard",
              "Pauses a workflow when 3 nodes fail within 60 seconds.",
              document(
                  Map.of(
                      "id",
                      "failure-guard",
                      "when",
                      Map.of(
                          "event",
                          "NodeStatusChanged",
                          "status",
                          "FAILURE",
                          "count",
                          3,
                          "withinSeconds",
                          60),
                      "then",
                      Map.of(COMMAND, PAUSE, REASON, "3 nodes failed within 60 seconds")))),
          new SupervisorPreset(
              "approval-gate",
              "A stop of a workflow waits for a person to approve it.",
              document(
                  Map.of(
                      "id",
                      "approval-gate",
                      "requireApproval",
                      Map.of(
                          COMMAND,
                          "StopWorkflow",
                          "risk",
                          "HIGH",
                          REASON,
                          "stopping a workflow needs a person")))),
          new SupervisorPreset(
              "stall-watchdog",
              "Every 30 seconds, pauses a running workflow that has done nothing for 10 minutes."
                  + " It acts on its own, so list PauseWorkflowCommand in initiative.",
              document(
                  Map.of(
                      "id",
                      "stall-watchdog",
                      "when",
                      Map.of("every", "30s", "status", "RUNNING", "if", "idleSeconds > 600"),
                      "then",
                      Map.of(COMMAND, PAUSE, REASON, "no progress for more than 600 seconds")))),
          new SupervisorPreset(
              "too-many-running",
              "Every 30 seconds, pauses each running workflow while more than 10 are running."
                  + " It acts on its own, so list PauseWorkflowCommand in initiative.",
              document(
                  Map.of(
                      "id",
                      "too-many-running",
                      "when",
                      Map.of("every", "30s", "status", "RUNNING", "if", "read.RUNNING > 10"),
                      "then",
                      Map.of(COMMAND, PAUSE, REASON, "more than 10 runs are running")))));

  private static Map<String, Object> document(final Map<String, Object> rule) {
    return Map.of("schemaVersion", 1, "rules", List.of(rule));
  }
}
