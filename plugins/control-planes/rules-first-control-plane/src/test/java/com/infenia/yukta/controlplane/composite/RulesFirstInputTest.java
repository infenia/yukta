// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.decision.Answer;
import com.infenia.yukta.plugin.decision.DecisionModel;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Mono;

/** Input requests go to the rules first and to the model only when no rule acted. */
@NoArgsConstructor
class RulesFirstInputTest {

  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private final AtomicInteger asked = new AtomicInteger();

  private final DecisionModel model =
      (spec, state, questions) -> {
        asked.incrementAndGet();
        return Mono.just(Map.of("answer", new Answer.Choice("option_2", Map.of("option_2", 0.95))));
      };

  private ControlPlanePlugin plane(final boolean canAnswer) {
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("model", model);
    final Map<String, Object> settings =
        Map.of(
            "canAnswer",
            canAnswer,
            "maxRisk",
            "MEDIUM",
            "rules",
            List.of(
                Map.of(
                    "id", "staging",
                    "when",
                        Map.of(
                            "event", "InputRequested", "if", "context['environment'] == 'staging'"),
                    "then", Map.of("answer", Map.of("decision", "deploy")))),
            "model",
            Map.of("name", "m", "events", List.of("InputRequested")));
    return (ControlPlanePlugin)
        new RulesFirstControlPlaneFactory(beans.getBeanProvider(DecisionModel.class))
            .create(
                new SupervisorProfile(
                    "p", RulesFirstControlPlaneFactory.PROFILE_TYPE, settings, false));
  }

  private static ExecutionEvent requested(final String environment) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "key",
            InputKind.CHOICE,
            "Deploy or hold?",
            Map.of("environment", environment),
            List.of(new InputOption("deploy"), new InputOption("hold")),
            null,
            InputRisk.MEDIUM,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(5),
            TimeoutAction.FAIL,
            null,
            null,
            "ASK_INPUT");
    return new ExecutionEvent.InputRequested(
        new InputRequest(
            "r1",
            "key",
            "s",
            "wf",
            "e1",
            "ask",
            spec,
            InputState.OPEN,
            0,
            "auto",
            List.of(),
            null,
            null,
            NOW,
            NOW),
        NOW);
  }

  @Test
  void ruleAnswersFirstAndTheModelIsNotAsked() {
    final ControlDecision decision =
        Objects.requireNonNull(plane(true).decide(requested("staging")).block());

    assertThat(decision.source()).isEqualTo(Source.RULE);
    assertThat(decision.commands())
        .singleElement()
        .isInstanceOfSatisfying(
            AnswerInputCommand.class, answer -> assertThat(answer.decision()).isEqualTo("deploy"));
    assertThat(asked).hasValue(0);
  }

  @Test
  void modelAnswersWhenNoRuleFiredAndTheProfileAllowsIt() {
    final ControlDecision decision =
        Objects.requireNonNull(plane(true).decide(requested("production")).block());

    assertThat(decision.source()).isEqualTo(Source.MODEL);
    assertThat(decision.commands())
        .singleElement()
        .isInstanceOfSatisfying(
            AnswerInputCommand.class, answer -> assertThat(answer.decision()).isEqualTo("hold"));
    assertThat(asked).hasValue(1);
  }

  @Test
  void withoutCanAnswerTheModelOnlyAdvises() {
    final ControlDecision decision =
        Objects.requireNonNull(plane(false).decide(requested("production")).block());

    assertThat(decision.commands())
        .singleElement()
        .isInstanceOfSatisfying(
            EscalateInputCommand.class,
            escalate -> assertThat(escalate.advice()).contains("recommends 'hold'"));
  }
}
