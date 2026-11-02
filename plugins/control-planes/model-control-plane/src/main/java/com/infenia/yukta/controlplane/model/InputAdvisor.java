// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.decision.Answer;
import com.infenia.yukta.plugin.decision.DecisionModel;
import com.infenia.yukta.plugin.decision.ModelSpec;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Asks the model about an input request and turns its choice into an answer or an escalation.
 *
 * <p>The model only ever picks one key of a fixed question ({@link InputPrompt}), so its output is
 * structured by construction: a decision (an option id of the request, or {@link
 * InputPrompt#ESCALATE}), a confidence (the probability of the choice) and a rationale written here
 * from the two, prefixed with the model's name for the audit. Anything else (a key that was not
 * offered, an unreadable or missing answer, a probability outside 0 to 1, a model error or a model
 * slower than the decision timeout) is no opinion, and the request stays where it is.
 *
 * <p>An answer is sent only when the confidence reaches {@code min-confidence}, the request's risk
 * is not {@code HIGH} and the supervisor's profile may answer that risk ({@code canAnswer}, {@code
 * maxRisk}). Otherwise the request is escalated with the model's recommendation as advice, so the
 * person who answers next sees it. The server checks the same rules again on every answer.
 */
@Slf4j
final class InputAdvisor {

  /** Outcome of a reply that gives no usable answer. */
  private static final String NO_OPINION = "no_opinion";

  /** Name of the one question asked. */
  private static final String QUESTION = "answer";

  /** Asks the model. */
  private final DecisionModel model;

  /** Which model to ask. */
  private final ModelSpec spec;

  /** The model's name, for the rationale and the advice. */
  private final String modelName;

  /** The least confidence an answer needs. */
  private final double minConfidence;

  /** What the supervisor's profile allows. */
  private final SupervisorCapabilities capabilities;

  /** Counts the outcomes. */
  private final PluginMetricsReporter metrics;

  /** Longest wait for the model. */
  private final Duration timeout;

  /**
   * Creates the advisor.
   *
   * @param model asks the model
   * @param spec which model to ask
   * @param minConfidence the least confidence an answer needs
   * @param capabilities what the supervisor's profile allows
   * @param metrics counts the outcomes
   * @param timeout longest wait for the model; slower is no opinion
   */
  /* default */ InputAdvisor(
      final DecisionModel model,
      final ModelSpec spec,
      final double minConfidence,
      final SupervisorCapabilities capabilities,
      final PluginMetricsReporter metrics,
      final Duration timeout) {
    this.model = model;
    this.spec = spec;
    this.modelName = spec.model();
    this.minConfidence = minConfidence;
    this.capabilities = capabilities;
    this.metrics = metrics;
    this.timeout = timeout;
  }

  /**
   * Decides about a new or an escalated request.
   *
   * @param request the request, redacted
   * @param escalationReason why it was escalated, or {@code null} for a new request
   * @return the decision; no opinion when the model cannot help
   */
  /* default */ Mono<ControlDecision> decide(
      final InputRequest request, final @Nullable String escalationReason) {
    final Optional<InputPrompt> prompt =
        InputPrompt.answerable(request.spec().kind())
            ? InputPrompt.forRequest(request, escalationReason)
            : Optional.empty();
    return prompt.isEmpty()
        ? Mono.fromSupplier(() -> none("unsupported"))
        : model
            .answer(spec, prompt.get().state(), Map.of(QUESTION, prompt.get().question()))
            .timeout(timeout)
            .map(answers -> verdict(request, prompt.get(), answers.get(QUESTION), escalationReason))
            .onErrorResume(
                e -> {
                  final boolean slow = e instanceof TimeoutException;
                  log.atWarn()
                      .addKeyValue("requestId", request.id())
                      .log(slow ? "Decision model too slow; no opinion" : "Decision model failed");
                  return Mono.just(none(slow ? "timeout" : "model_error"));
                });
  }

  private ControlDecision verdict(
      final InputRequest request,
      final InputPrompt prompt,
      final @Nullable Answer answer,
      final @Nullable String escalationReason) {
    final ControlDecision decision;
    if (answer instanceof final Answer.Choice choice && isProbability(choice.probability())) {
      if (InputPrompt.ESCALATE.equals(choice.choice())) {
        decision =
            escalationReason == null
                ? escalate(
                    request,
                    "model " + modelName + " found no safe answer",
                    "Model " + modelName + " recommends that a person decides.",
                    choice.probability())
                : none(NO_OPINION);
      } else {
        decision =
            prompt
                .optionFor(choice.choice())
                .map(option -> act(request, option, choice.probability(), escalationReason))
                .orElseGet(() -> none("invalid_answer"));
      }
    } else {
      decision = none(NO_OPINION);
    }
    return decision;
  }

  /** Answers when allowed and sure enough; otherwise hands the request up with the advice. */
  private ControlDecision act(
      final InputRequest request,
      final String option,
      final double confidence,
      final @Nullable String escalationReason) {
    final InputRisk risk = request.spec().risk();
    final Optional<String> held = withheldBecause(risk, confidence);
    final ControlDecision decision;
    if (held.isEmpty()) {
      metrics.increment(ModelControlPlane.PLUGIN_ID, ModelControlPlane.METRIC, "answered");
      decision =
          new ControlDecision(
              List.of(
                  new ExecutionControlCommand.AnswerInputCommand(
                      request.executionId(),
                      request.id(),
                      request.version(),
                      option,
                      null,
                      Map.of(),
                      "model "
                          + modelName
                          + ": chose '"
                          + option
                          + "' with confidence "
                          + format(confidence),
                      confidence)),
              confidence,
              Source.MODEL);
    } else if (escalationReason == null) {
      decision =
          escalate(
              request,
              "model " + modelName + " did not answer: " + held.get(),
              "Model "
                  + modelName
                  + " recommends '"
                  + option
                  + "' (confidence "
                  + format(confidence)
                  + "); it did not answer because "
                  + held.get()
                  + ".",
              confidence);
    } else {
      // already handed up: escalating again changes nothing
      decision = none(NO_OPINION);
    }
    return decision;
  }

  /** Why the model may not answer, or empty when it may. */
  private Optional<String> withheldBecause(final InputRisk risk, final double confidence) {
    final Optional<String> reason;
    if (risk == InputRisk.HIGH) {
      reason = Optional.of("a request of risk HIGH is answered by a person only");
    } else if (capabilities.mayAnswer(risk)) {
      reason =
          confidence < minConfidence
              ? Optional.of("its confidence is below " + format(minConfidence))
              : Optional.empty();
    } else {
      reason =
          Optional.of(
              capabilities.canAnswer()
                  ? "its profile answers up to risk " + capabilities.maxRisk() + ", not " + risk
                  : "its profile may not answer (canAnswer is off)");
    }
    return reason;
  }

  private ControlDecision escalate(
      final InputRequest request,
      final String reason,
      final String advice,
      final double confidence) {
    metrics.increment(ModelControlPlane.PLUGIN_ID, ModelControlPlane.METRIC, "escalated");
    return new ControlDecision(
        List.of(
            new ExecutionControlCommand.EscalateInputCommand(
                request.executionId(), request.id(), reason, advice)),
        confidence,
        Source.MODEL);
  }

  private ControlDecision none(final String outcome) {
    metrics.increment(ModelControlPlane.PLUGIN_ID, ModelControlPlane.METRIC, outcome);
    return ControlDecision.none(Source.MODEL);
  }

  private static boolean isProbability(final double value) {
    return value >= 0 && value <= 1;
  }

  private static String format(final double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
