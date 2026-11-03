// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class SupervisorResultMapperTest {

  private static Map<String, Object> entry(final String type, final Object... pairs) {
    final Map<String, Object> m = new HashMap<>();
    m.put("type", type);
    for (int i = 0; i < pairs.length; i += 2) {
      m.put((String) pairs[i], pairs[i + 1]);
    }
    return m;
  }

  private static List<Object> results(final Object... entries) {
    return List.of(Map.of("commands", List.of(entries)));
  }

  private static SupervisorResultMapper.Mapped map(final Set<String> allowed, final Object... e) {
    return SupervisorResultMapper.map(results(e), allowed, "run", 10);
  }

  @Test
  void alwaysMappableTypesNeedNoGrant() {
    final var mapped =
        map(
            Set.of(),
            entry(
                "AnswerInput",
                "requestId",
                "r1",
                "decision",
                "approve",
                "text",
                "t",
                "rationale",
                "why",
                "confidence",
                0.9,
                "expectedVersion",
                3,
                "data",
                Map.of("k", 1)),
            entry("EscalateInputCommand", "requestId", "r1", "reason", "hard", "advice", "a"),
            entry("PauseWorkflow"),
            entry("ResumeWorkflow", "executionId", "other"),
            entry("PauseNode", "nodeId", "n"),
            entry("ResumeNode", "nodeId", "n"),
            entry("SetPriority", "priority", 3));
    assertThat(mapped.dropped()).isZero();
    assertThat(mapped.commands()).hasSize(7);
    assertThat(mapped.commands().get(0))
        .isEqualTo(
            new ExecutionControlCommand.AnswerInputCommand(
                "run", "r1", 3L, "approve", "t", Map.of("k", 1), "why", 0.9));
    assertThat(mapped.commands().get(3))
        .isEqualTo(new ExecutionControlCommand.ResumeWorkflowCommand("other"));
  }

  @Test
  void answerInputOptionalPartsMayBeLeftOut() {
    final var mapped = map(Set.of(), entry("AnswerInput", "requestId", "r", "decision", "d"));
    assertThat(mapped.commands())
        .containsExactly(
            new ExecutionControlCommand.AnswerInputCommand(
                "run", "r", null, "d", null, Map.of(), null, null));
  }

  @Test
  void otherTypesNeedTheGrantByName() {
    final var all =
        new Object[] {
          entry("StopWorkflow", "reason", "x", "mode", "CANCEL"),
          entry("StopWorkflow"),
          entry("StopNode", "nodeId", "n", "immediate", true),
          entry("SkipNode", "nodeId", "n"),
          entry("SkipNode", "nodeId", "n", "skip", false),
          entry("Restart"),
          entry("RestartFromNode", "fromNodeId", "n"),
          entry("SetLimits", "maxConcurrent", 4, "maxPerWorkflow", 2),
          entry("SetBreakpoint", "nodeId", "n", "field", "payload.x", "equalsValue", "v")
        };
    assertThat(map(Set.of(), all).commands()).isEmpty();
    assertThat(map(Set.of(), all).dropped()).isEqualTo(9);
    assertThat(map(null, all).commands()).isEmpty();
    final Set<String> granted =
        Set.of(
            "StopWorkflowCommand",
            "StopNodeCommand",
            "SkipNodeCommand",
            "RestartCommand",
            "RestartFromNodeCommand",
            "SetLimitsCommand",
            "SetBreakpointCommand");
    final var mapped = map(granted, all);
    assertThat(mapped.commands()).hasSize(9);
    assertThat(mapped.dropped()).isZero();
  }

  @Test
  void unknownMalformedAndOverTheCapAreDroppedAndCounted() {
    final List<Object> many = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      many.add(entry("PauseWorkflow"));
    }
    many.add("not a map");
    many.add(Map.of("no", "type"));
    many.add(entry("Nonsense"));
    many.add(entry("PauseNode"));
    many.add(entry("SetPriority", "priority", "high"));
    many.add(entry("StopWorkflow", "mode", "NOPE"));
    final var mapped =
        SupervisorResultMapper.map(
            List.of(Map.of("commands", many)), Set.of("StopWorkflowCommand"), "run", 2);
    assertThat(mapped.commands()).hasSize(2);
    assertThat(mapped.dropped()).isEqualTo(8);
  }

  @Test
  void aResultWithoutCommandsGivesNothing() {
    assertThat(SupervisorResultMapper.map(List.of(), Set.of(), "run", 10).commands()).isEmpty();
    assertThat(
            SupervisorResultMapper.map(List.of("x", Map.of("a", 1)), Set.of(), "run", 10).dropped())
        .isZero();
    assertThat(
            SupervisorResultMapper.map(List.of(Map.of("commands", "no")), Set.of(), "run", 10)
                .commands())
        .isEmpty();
  }
}
