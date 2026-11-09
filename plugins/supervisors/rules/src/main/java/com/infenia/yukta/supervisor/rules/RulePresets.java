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

  /** The presets, each a complete rule document of version 1. */
  public static final List<SupervisorPreset> ALL =
      List.of(
          new SupervisorPreset(
              "failure-guard",
              "Pauses a workflow when 3 nodes fail within 60 seconds.",
              Map.of(
                  "schemaVersion",
                  1,
                  "rules",
                  List.of(
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
                          Map.of(
                              "command",
                              "PauseWorkflow",
                              "reason",
                              "3 nodes failed within 60 seconds"))))),
          new SupervisorPreset(
              "approval-gate",
              "A stop of a workflow waits for a person to approve it.",
              Map.of(
                  "schemaVersion",
                  1,
                  "rules",
                  List.of(
                      Map.of(
                          "id",
                          "approval-gate",
                          "requireApproval",
                          Map.of(
                              "command",
                              "StopWorkflow",
                              "risk",
                              "HIGH",
                              "reason",
                              "stopping a workflow needs a person"))))),
          new SupervisorPreset(
              "stall-watchdog",
              "Every 30 seconds, pauses a running workflow that has done nothing for 10 minutes."
                  + " It acts on its own, so list PauseWorkflowCommand in initiative.",
              LegacyGuards.stallWatchdog(Map.of("maxSilenceSeconds", 600, "checkEverySeconds", 30))
                  .rules()),
          new SupervisorPreset(
              "too-many-running",
              "Every 30 seconds, pauses each running workflow while more than 10 are running."
                  + " It acts on its own, so list PauseWorkflowCommand in initiative.",
              Map.of(
                  "schemaVersion",
                  1,
                  "rules",
                  List.of(
                      Map.of(
                          "id",
                          "too-many-running",
                          "when",
                          Map.of("every", "30s", "status", "RUNNING", "if", "read.RUNNING > 10"),
                          "then",
                          Map.of(
                              "command",
                              "PauseWorkflow",
                              "reason",
                              "more than 10 runs are running"))))));
}
