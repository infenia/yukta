// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.decision.Answer;
import com.infenia.yukta.plugin.decision.DecisionModel;
import com.infenia.yukta.plugin.decision.ModelSpec;
import com.infenia.yukta.plugin.decision.Question;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The model plane answers input requests with confidence or escalates them with advice. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class ModelControlPlaneInputTest {

  private static final SupervisorCapabilities ANSWERS_MEDIUM =
      new SupervisorCapabilities(null, true, InputRisk.MEDIUM);

  private final List<String> counted = new CopyOnWriteArrayList<>();

  private final PluginMetricsReporter reporter = (node, metric, outcome) -> counted.add(outcome);

  /** Answers with a fixed reply and counts the calls. */
  private static final class FakeModel implements DecisionModel {
    private final AtomicInteger calls = new AtomicInteger();
    private final Mono<Map<String, Answer>> reply;

    /* default */ FakeModel(final Mono<Map<String, Answer>> reply) {
      this.reply = reply;
    }

    @Override
    public Mono<Map<String, Answer>> answer(
        final ModelSpec spec, final String state, final Map<String, Question> questions) {
      calls.incrementAndGet();
      return reply;
    }
  }

  private static FakeModel choosing(final String key, final double probability) {
    return new FakeModel(
        Mono.just(Map.of("answer", new Answer.Choice(key, Map.of(key, probability)))));
  }

  private static ModelPlaneSettings settings(final Set<String> events) {
    return new ModelPlaneSettings(
        null,
        null,
        "judge",
        null,
        null,
        Duration.ofSeconds(1),
        0.6,
        0.8,
        null,
        null,
        events,
        null,
        null,
        0);
  }

  private ModelControlPlane plane(
      final DecisionModel model, final SupervisorCapabilities capabilities) {
    return new ModelControlPlane(
        model, settings(Set.of("InputRequested", "InputEscalated")), reporter, capabilities);
  }

  private static ExecutionEvent requested(final InputRequest request) {
    return new ExecutionEvent.InputRequested(request, InputRequests.NOW);
  }

  private static List<ExecutionControlCommand> commands(final ControlDecision decision) {
    return Objects.requireNonNull(decision).commands();
  }

  @Test
  void confidentAnswerWithinMaxRiskAnswersWithTheOptionIdAndTheModelInTheRationale() {
    final ControlDecision decision =
        plane(choosing("option_1", 0.93), ANSWERS_MEDIUM)
            .decide(requested(InputRequests.choice(InputRisk.MEDIUM)))
            .block();

    assertThat(Objects.requireNonNull(decision).source()).isEqualTo(Source.MODEL);
    assertThat(decision.confidence()).isEqualTo(0.93);
    assertThat(commands(decision))
        .containsExactly(
            new AnswerInputCommand(
                "e1",
                "r1",
                2L,
                "deploy",
                null,
                Map.of(),
                "model judge: chose 'deploy' with confidence 0.93",
                0.93));
    assertThat(counted).containsExactly("answered");
  }

  @Test
  void approvalWithoutOptionsOffersApproveAndReject() {
    final InputRequest approval =
        InputRequests.request(InputKind.APPROVAL, InputRisk.LOW, "Ship it?", Map.of(), List.of());

    final ControlDecision decision =
        plane(choosing("option_2", 0.9), ANSWERS_MEDIUM).decide(requested(approval)).block();

    assertThat(commands(decision))
        .singleElement()
        .isInstanceOfSatisfying(
            AnswerInputCommand.class, answer -> assertThat(answer.decision()).isEqualTo("reject"));
  }

  @Test
  void lowConfidenceEscalatesWithTheRecommendationAsAdvice() {
    final ControlDecision decision =
        plane(choosing("option_2", 0.62), ANSWERS_MEDIUM)
            .decide(requested(InputRequests.choice(InputRisk.LOW)))
            .block();

    assertThat(commands(decision))
        .containsExactly(
            new EscalateInputCommand(
                "e1",
                "r1",
                "model judge did not answer: its confidence is below 0.80",
                "Model judge recommends 'hold' (confidence 0.62); it did not answer because its"
                    + " confidence is below 0.80."));
    assertThat(counted).containsExactly("escalated");
  }

  @Test
  void highRiskIsNeverAnsweredButEscalatedWithAdvice() {
    final ControlDecision decision =
        plane(choosing("option_1", 1.0), new SupervisorCapabilities(null, true, InputRisk.HIGH))
            .decide(requested(InputRequests.choice(InputRisk.HIGH)))
            .block();

    assertThat(commands(decision))
        .singleElement()
        .isInstanceOfSatisfying(
            EscalateInputCommand.class,
            escalate -> {
              assertThat(escalate.reason()).contains("risk HIGH is answered by a person only");
              assertThat(escalate.advice()).startsWith("Model judge recommends 'deploy'");
            });
  }

  @Test
  void riskAboveTheProfileOrNoPermissionEscalates() {
    final ControlDecision aboveMax =
        plane(choosing("option_1", 1.0), new SupervisorCapabilities(null, true, InputRisk.LOW))
            .decide(requested(InputRequests.choice(InputRisk.MEDIUM)))
            .block();
    final ControlDecision notAllowed =
        new ModelControlPlane(
                choosing("option_1", 1.0),
                settings(Set.of("InputRequested")),
                ModelControlPlane.PROPERTY_CAPABILITIES)
            .decide(requested(InputRequests.choice(InputRisk.LOW)))
            .block();

    assertThat(commands(aboveMax))
        .singleElement()
        .isInstanceOfSatisfying(
            EscalateInputCommand.class,
            escalate ->
                assertThat(escalate.reason()).contains("answers up to risk LOW, not MEDIUM"));
    assertThat(commands(notAllowed))
        .singleElement()
        .isInstanceOfSatisfying(
            EscalateInputCommand.class,
            escalate -> assertThat(escalate.reason()).contains("canAnswer is off"));
  }

  @Test
  void modelThatChoosesEscalateHandsTheRequestToPerson() {
    final ControlDecision decision =
        plane(choosing(InputPrompt.ESCALATE, 0.7), ANSWERS_MEDIUM)
            .decide(requested(InputRequests.choice(InputRisk.LOW)))
            .block();

    assertThat(commands(decision))
        .containsExactly(
            new EscalateInputCommand(
                "e1",
                "r1",
                "model judge found no safe answer",
                "Model judge recommends that a person decides."));
  }

  @Test
  void decisionThatIsNotAnOfferedOptionIsNoOpinion() {
    final ControlDecision rawId =
        plane(choosing("deploy", 1.0), ANSWERS_MEDIUM)
            .decide(requested(InputRequests.choice(InputRisk.LOW)))
            .block();
    final ControlDecision beyond =
        plane(choosing("option_3", 1.0), ANSWERS_MEDIUM)
            .decide(requested(InputRequests.choice(InputRisk.LOW)))
            .block();

    assertThat(commands(rawId)).isEmpty();
    assertThat(commands(beyond)).isEmpty();
    assertThat(counted).containsExactly("invalid_answer", "invalid_answer");
  }

  @Test
  void malformedOutputIsNoOpinion() {
    final FakeModel wrongShape = new FakeModel(Mono.just(Map.of("answer", new Answer.YesNo(0.99))));
    final FakeModel missing = new FakeModel(Mono.just(Map.of("other", new Answer.Score(1))));
    final FakeModel badProbability =
        new FakeModel(
            Mono.just(Map.of("answer", new Answer.Choice("option_1", Map.of("option_1", 1.7)))));
    final FakeModel notNumber =
        new FakeModel(
            Mono.just(
                Map.of("answer", new Answer.Choice("option_1", Map.of("option_1", Double.NaN)))));
    final FakeModel failing = new FakeModel(Mono.error(new IllegalStateException("not JSON")));

    for (final FakeModel model : List.of(wrongShape, missing, badProbability, notNumber, failing)) {
      assertThat(
              commands(
                  plane(model, ANSWERS_MEDIUM)
                      .decide(requested(InputRequests.choice(InputRisk.LOW)))
                      .block()))
          .isEmpty();
    }
    assertThat(counted)
        .containsExactly("no_opinion", "no_opinion", "no_opinion", "no_opinion", "model_error");
  }

  @Test
  void slowModelIsNoOpinionAfterTheDecisionTimeout() {
    final ModelControlPlane plane = plane(new FakeModel(Mono.never()), ANSWERS_MEDIUM);

    StepVerifier.withVirtualTime(() -> plane.decide(requested(InputRequests.choice(InputRisk.LOW))))
        .thenAwait(plane.decisionTimeout())
        .assertNext(decision -> assertThat(decision.commands()).isEmpty())
        .verifyComplete();
    assertThat(counted).containsExactly("timeout");
  }

  @Test
  void kindsWithoutOptionsGetNoOpinionWithoutAskingTheModel() {
    final FakeModel model = choosing("option_1", 1.0);
    final ModelControlPlane plane = plane(model, ANSWERS_MEDIUM);

    for (final InputKind kind : List.of(InputKind.TEXT, InputKind.STRUCTURED, InputKind.QUERY)) {
      assertThat(
              commands(
                  plane
                      .decide(
                          requested(
                              InputRequests.request(
                                  kind, InputRisk.LOW, "Why?", Map.of(), List.of())))
                      .block()))
          .isEmpty();
    }
    assertThat(model.calls).hasValue(0);
    assertThat(counted).containsExactly("unsupported", "unsupported", "unsupported");
  }

  @Test
  void escalatedRequestIsAnsweredWhenSureButNeverEscalatedAgain() {
    final ExecutionEvent escalated =
        new ExecutionEvent.InputEscalated(
            InputRequests.choice(InputRisk.LOW), "tier timeout", InputRequests.NOW);

    final ControlDecision sure =
        plane(choosing("option_1", 0.95), ANSWERS_MEDIUM).decide(escalated).block();
    final ControlDecision unsure =
        plane(choosing("option_1", 0.5), ANSWERS_MEDIUM).decide(escalated).block();
    final ControlDecision giveUp =
        plane(choosing(InputPrompt.ESCALATE, 0.9), ANSWERS_MEDIUM).decide(escalated).block();

    assertThat(commands(sure)).singleElement().isInstanceOf(AnswerInputCommand.class);
    assertThat(commands(unsure)).isEmpty();
    assertThat(commands(giveUp)).isEmpty();
  }

  @Test
  void inputEventsNotListedInEventsAreNotJudged() {
    final FakeModel model = choosing("option_1", 1.0);
    final ModelControlPlane plane =
        new ModelControlPlane(model, settings(null), reporter, ANSWERS_MEDIUM);

    assertThat(commands(plane.decide(requested(InputRequests.choice(InputRisk.LOW))).block()))
        .isEmpty();
    assertThat(
            commands(
                plane
                    .decide(
                        new ExecutionEvent.InputEscalated(
                            InputRequests.choice(InputRisk.LOW), "x", InputRequests.NOW))
                    .block()))
        .isEmpty();
    assertThat(model.calls).hasValue(0);
  }

  @Test
  void inputRequestsCountTowardTheCallLimit() {
    final FakeModel model = choosing("option_1", 1.0);
    final ModelControlPlane plane =
        new ModelControlPlane(
            model,
            new ModelPlaneSettings(
                null,
                null,
                "judge",
                null,
                null,
                Duration.ofSeconds(1),
                0.6,
                0.8,
                null,
                null,
                Set.of("InputRequested"),
                null,
                null,
                1),
            reporter,
            ANSWERS_MEDIUM);

    plane.decide(requested(InputRequests.choice(InputRisk.LOW))).block();
    plane.decide(requested(InputRequests.choice(InputRisk.LOW))).block();

    assertThat(model.calls).hasValue(1);
    assertThat(counted).containsExactly("answered", "skipped");
  }
}
