// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Rules that answer or escalate input requests. */
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class RuleInputActionsTest {

  private static final String STAGING_RULES =
      """
      {"rules":[
        {"id":"approve-staging",
         "when":{"event":"InputRequested",
                 "if":"risk == 'LOW' and context['environment'] == 'staging'"},
         "then":{"answer":{"decision":"approve","rationale":"policy 7: low risk deploys",
                           "confidence":0.9,"text":"go"}}},
        {"id":"escalate-rest",
         "when":{"event":"InputRequested",
                 "if":"not (risk == 'LOW' and context['environment'] == 'staging')"},
         "then":{"escalate":{"reason":"not covered by policy 7","advice":"check the change log"}}}
      ]}
      """;

  private static final SupervisorCapabilities ANSWERS_LOW =
      new SupervisorCapabilities(null, true, InputRisk.LOW);

  private static RuleSupervisor supervisor(final String json) {
    return new RuleSupervisor(RuleSupervisor.read(json), Clock.systemUTC(), ANSWERS_LOW);
  }

  private static List<ExecutionControlCommand> run(
      final RuleSupervisor supervisor, final ExecutionEvent... events) {
    return supervisor.supervise(Flux.just(events)).collectList().block();
  }

  private static String rule(final String when, final String then) {
    return "{\"rules\":[{\"id\":\"r\",\"when\":" + when + ",\"then\":" + then + "}]}";
  }

  @Test
  void answerRuleAnswersTheRequestOfTheEventAtTheVersionItSaw() {
    final List<ExecutionControlCommand> commands =
        run(
            supervisor(STAGING_RULES),
            InputEvents.requested(InputRisk.LOW, Map.of("environment", "staging")));

    assertThat(commands)
        .containsExactly(
            new AnswerInputCommand(
                "e1",
                "r1",
                3L,
                "approve",
                "go",
                Map.of(),
                "rule approve-staging: policy 7: low risk deploys",
                0.9));
  }

  @Test
  void escalateRuleHandsEverythingElseUpWithAdvice() {
    final List<ExecutionControlCommand> commands =
        run(
            supervisor(STAGING_RULES),
            InputEvents.requested(InputRisk.MEDIUM, Map.of("environment", "staging")),
            InputEvents.requested(InputRisk.LOW, Map.of("environment", "production")),
            InputEvents.requested(InputRisk.LOW, Map.of()));

    assertThat(commands)
        .hasSize(3)
        .allSatisfy(
            command ->
                assertThat(command)
                    .isEqualTo(
                        new EscalateInputCommand(
                            "e1", "r1", "not covered by policy 7", "check the change log")));
  }

  @Test
  void answerWithoutRationaleOrConfidenceIsSureAndNamesTheRule() {
    final RuleSupervisor rules =
        supervisor(
            rule("{\"event\":\"InputEscalated\"}", "{\"answer\":{\"decision\":\"reject\"}}"));

    assertThat(run(rules, InputEvents.escalated(InputRisk.LOW)))
        .containsExactly(
            new AnswerInputCommand("e1", "r1", 4L, "reject", null, Map.of(), "rule r", 1.0));
  }

  @Test
  void escalateWithoutReasonNamesTheRule() {
    final RuleSupervisor rules = supervisor(rule("{}", "{\"escalate\":{\"reason\":\" \"}}"));
    final RuleSupervisor noReason = supervisor(rule("{}", "{\"escalate\":{}}"));

    assertThat(run(rules, InputEvents.escalated(InputRisk.HIGH)))
        .containsExactly(new EscalateInputCommand("e1", "r1", "escalated by rule r", null));
    assertThat(run(noReason, InputEvents.requested(InputRisk.HIGH, Map.of())))
        .containsExactly(new EscalateInputCommand("e1", "r1", "escalated by rule r", null));
  }

  @Test
  void inputRuleWithoutEventKindIgnoresEventsWithoutRequest() {
    final RuleSupervisor rules = supervisor(rule("{}", "{\"answer\":{\"decision\":\"approve\"}}"));
    final ExecutionEvent node =
        new ExecutionEvent.NodeStatusChanged(
            "e1", "s", "wf", "n1", "HTTP_REQUEST", "FAILURE", Map.of(), InputEvents.NOW);
    final ExecutionEvent answered =
        new ExecutionEvent.InputExpired("e1", "s", "wf", "r1", InputEvents.NOW);

    assertThat(run(rules, node, answered)).isEmpty();
    assertThat(run(rules, InputEvents.requested(InputRisk.LOW, Map.of()))).hasSize(1);
  }

  @Test
  void inputRuleCannotBuildCommandForEventWithoutRequest() {
    final EventRule rule =
        new EventRule(
            "r",
            new RuleSet.When(null, null, null, null, null, null, null, null),
            new RuleSet.Then(
                null,
                null,
                null,
                null,
                null,
                new RuleSet.Answer("approve", null, null, null),
                null));
    final ExecutionEvent node =
        new ExecutionEvent.WorkflowStatusChanged("e1", "s", "wf", "RUNNING", InputEvents.NOW);

    assertThat(rule.answers()).isTrue();
    assertThat(rule.restarts()).isFalse();
    assertThatThrownBy(() -> rule.command(node, "new"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown command");
  }

  @Test
  void conditionThatCannotBeEvaluatedDoesNotAnswer() {
    final RuleSupervisor rules =
        supervisor(
            rule(
                "{\"event\":\"InputRequested\",\"if\":\"context.missing.deeper == 'x'\"}",
                "{\"answer\":{\"decision\":\"approve\"}}"));

    assertThat(run(rules, InputEvents.requested(InputRisk.LOW, Map.of()))).isEmpty();
  }

  @Test
  void invalidInputRulesFailWhenTheyAreLoaded() {
    assertThatThrownBy(
            () ->
                supervisor(
                    rule(
                        "{\"event\":\"NodeStatusChanged\"}",
                        "{\"answer\":{\"decision\":\"approve\"}}")))
        .hasMessageContaining("must react to InputRequested or InputEscalated");
    assertThatThrownBy(
            () ->
                supervisor(
                    rule(
                        "{}",
                        "{\"command\":\"PauseWorkflow\",\"answer\":{\"decision\":\"approve\"}}")))
        .hasMessageContaining("exactly one of command, answer and escalate");
    assertThatThrownBy(
            () -> supervisor(rule("{}", "{\"answer\":{\"decision\":\"approve\"},\"escalate\":{}}")))
        .hasMessageContaining("exactly one of command, answer and escalate");
    assertThatThrownBy(() -> supervisor(rule("{}", "{\"answer\":{\"text\":\"no decision\"}}")))
        .hasMessageContaining("invalid answer or escalation")
        .hasMessageContaining("decision");
    assertThatThrownBy(
            () ->
                supervisor(rule("{}", "{\"answer\":{\"decision\":\"approve\",\"confidence\":2}}")))
        .hasMessageContaining("confidence");
  }

  @Test
  void misspelledAnswerFieldFailsWhenTheRulesAreRead() {
    assertThatThrownBy(() -> RuleSupervisor.read(rule("{}", "{\"answer\":{\"decison\":\"x\"}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("decison");
  }

  @Test
  void answerRulesWithoutCanAnswerAreReportedWhenLoaded() {
    final RuleSet rules = RuleSupervisor.read(STAGING_RULES);

    assertThat(new RuleSupervisor(rules, Clock.systemUTC()).warning())
        .hasValueSatisfying(
            warning ->
                assertThat(warning).contains("[approve-staging]").contains("canAnswer is off"));
    assertThat(
            new RuleSupervisor(
                    rules,
                    Clock.systemUTC(),
                    new SupervisorCapabilities(null, false, InputRisk.MEDIUM))
                .warning())
        .isPresent();
    assertThat(new RuleSupervisor(rules, Clock.systemUTC(), ANSWERS_LOW).warning()).isEmpty();
  }

  @Test
  void escalateOnlyAndCommandRulesNeedNoPermissionToAnswer() {
    final RuleSet escalating = RuleSupervisor.read(rule("{}", "{\"escalate\":{}}"));
    final RuleSet pausing = RuleSupervisor.read(rule("{}", "{\"command\":\"PauseWorkflow\"}"));

    assertThat(new RuleSupervisor(escalating, Clock.systemUTC()).warning()).isEmpty();
    assertThat(new RuleSupervisor(pausing, Clock.systemUTC()).warning()).isEmpty();
  }

  @Test
  void profileSettingsLeaveTheCapabilitiesOutButStayStrict() {
    final Map<String, Object> settings =
        Map.of(
            "canAnswer",
            true,
            "maxRisk",
            "MEDIUM",
            "allowedCommands",
            List.of("AnswerInputCommand"),
            "rules",
            List.of());

    assertThat(RuleSupervisor.read(settings).rules()).isEmpty();
    assertThatThrownBy(() -> RuleSupervisor.read(Map.of("rulez", List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
