// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.process;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.UseConcurrentHashMap"
})
class DirectiveJsonTest {

  @Test
  void commandLineBecomesCommandDirective() {
    assertThat(
            DirectiveJson.read(
                Map.of(
                    "type", "command",
                    "key", "k",
                    "cause", "event:e1",
                    "reason", "why",
                    "ifStatus", "RUNNING",
                    "command", Map.of("type", "PauseWorkflow", "executionId", "run1"))))
        .contains(
            new Directive.Command(
                "k", new PauseWorkflowCommand("run1"), Cause.event("e1"), "why", "RUNNING"));
  }

  @Test
  void everyCauseIsRead() {
    for (final String cause : new String[] {null, "initiative", "tick", "answers:q"}) {
      final Map<String, Object> line = new java.util.HashMap<>();
      line.put("type", "command");
      line.put("key", "k");
      line.put("cause", cause);
      line.put("command", Map.of("type", "PauseWorkflow", "executionId", "r"));
      assertThat(DirectiveJson.read(line)).isPresent();
    }
  }

  @Test
  void verdictsNoOpinionsAndAnswersAreRead() {
    assertThat(
            DirectiveJson.read(
                Map.of("type", "verdict", "answers", "r1", "verdict", "deny", "reason", "no")))
        .contains(new Directive.ReviewVerdict("r1", Directive.Verdict.DENY, "no"));
    assertThat(DirectiveJson.read(Map.of("type", "noOpinion", "answers", "r2")))
        .contains(new Directive.NoOpinion("r2"));
    assertThat(
            DirectiveJson.read(
                Map.of("type", "delegationAnswer", "answers", "d1", "answer", Map.of("ok", true))))
        .contains(new Directive.DelegationAnswer("d1", Map.of("ok", true)));
  }

  @Test
  void anythingElseIsRefused() {
    final List<Map<String, Object>> bad =
        List.of(
            Map.of(),
            Map.of("type", "mystery"),
            Map.of("type", 5),
            Map.of("type", "verdict", "answers", "r", "verdict", "maybe"),
            Map.of("type", "verdict", "verdict", "ALLOW"),
            Map.of("type", "noOpinion"),
            Map.of("type", "delegationAnswer", "answers", "d", "answer", "text"),
            Map.of("type", "command", "key", "k"),
            Map.of("type", "command", "key", "k", "command", Map.of("type", "PauseWorkflow")),
            Map.of(
                "type",
                "command",
                "key",
                "k",
                "command",
                Map.of("type", "Nope", "executionId", "r")),
            Map.of(
                "type", "command", "command", Map.of("type", "PauseWorkflow", "executionId", "r")),
            Map.of(
                "type",
                "command",
                "key",
                "k",
                "cause",
                "whenever",
                "command",
                Map.of("type", "PauseWorkflow", "executionId", "r")));
    for (final Map<String, Object> line : bad) {
      assertThat(DirectiveJson.read(line)).as(line.toString()).isEmpty();
    }
  }
}
