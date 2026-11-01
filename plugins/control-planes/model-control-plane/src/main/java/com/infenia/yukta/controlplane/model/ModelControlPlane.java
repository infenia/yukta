// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import com.infenia.yukta.controlplane.model.ModelPlaneSettings.Command;
import com.infenia.yukta.controlplane.model.ModelPlaneSettings.Option;
import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.decision.Answer;
import com.infenia.yukta.plugin.decision.DecisionModel;
import com.infenia.yukta.plugin.decision.ModelSpec;
import com.infenia.yukta.plugin.decision.Question;
import com.infenia.yukta.plugin.decision.StateText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The model-based control plane: asks a {@link DecisionModel} (Ollama System One or any chat model)
 * what to do about each event and turns the answer into a command. The question, the answers it may
 * give, the command each triggers and the kinds of event it is asked about all come from {@link
 * ModelPlaneSettings}. Enabled by setting {@code yukta.control-plane.model.name}.
 *
 * <p>It only acts when it is sure: an answer less probable than {@code min-probability}, an unknown
 * answer, a failing or slow model and an unreadable reply all count as no opinion, so the model can
 * never hold up the bus. {@code max-calls-per-minute} caps the model calls: events beyond it get no
 * opinion at once. By default it can only pause; a stop has to be configured.
 */
@Slf4j
@Component
@EnableConfigurationProperties(ModelPlaneSettings.class)
@ConditionalOnProperty("yukta.control-plane.model.name")
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class ModelControlPlane implements ControlPlanePlugin {

  /** Identifies this plugin in the audit log. */
  public static final String PLUGIN_ID = "model-control-plane";

  /** Metric name of the counts this plugin reports. */
  private static final String METRIC = "model_control_plane";

  /** Name of the one question asked. */
  private static final String QUESTION = "action";

  /** Length of the window {@code max-calls-per-minute} counts in. */
  private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

  /** When the current window began. */
  private final AtomicLong windowStart = new AtomicLong(System.nanoTime());

  /** Model calls made in the current window. */
  private final AtomicInteger callsInWindow = new AtomicInteger();

  /** Asks the model. */
  private final DecisionModel model;

  /** The configuration. */
  private final ModelPlaneSettings settings;

  /** Which model to ask. */
  private final ModelSpec spec;

  /** The question put to the model. */
  private final Question question;

  /** Counts how often the model gave no usable answer. */
  private final PluginMetricsReporter metrics;

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param settings the configuration
   */
  public ModelControlPlane(final DecisionModel model, final ModelPlaneSettings settings) {
    this(model, settings, PluginMetricsReporter.NOOP);
  }

  /**
   * Creates the plugin with metrics.
   *
   * @param model asks the model
   * @param settings the configuration
   * @param reporters where {@code yukta.plugin.events{plugin=model_control_plane}} is counted, with
   *     the outcomes {@code decided}, {@code no_opinion} (unsure, unknown answer or low
   *     probability) and {@code model_error} (down, slow or unreadable); alert on the last two
   */
  @Autowired
  public ModelControlPlane(
      final DecisionModel model,
      final ModelPlaneSettings settings,
      final ObjectProvider<PluginMetricsReporter> reporters) {
    this(model, settings, reporters.getIfAvailable(() -> PluginMetricsReporter.NOOP));
  }

  /* default */ ModelControlPlane(
      final DecisionModel model,
      final ModelPlaneSettings settings,
      final PluginMetricsReporter metrics) {
    this.metrics = metrics;
    this.model = model;
    this.settings = settings;
    this.spec =
        new ModelSpec(
            settings.backend(),
            settings.provider(),
            settings.name(),
            settings.baseUrl(),
            settings.apiKey(),
            settings.timeout(),
            settings.keepAlive(),
            settings.numCtx());
    final Map<String, String> criteria = new LinkedHashMap<>();
    settings.options().forEach((key, option) -> criteria.put(key, option.description()));
    this.question = new Question.Choice(settings.instructions(), criteria);
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return PLUGIN_ID;
  }

  /** True if another model call fits in the window; the over-limit events are skipped. */
  private boolean withinLimit() {
    final int max = settings.maxCallsPerMinute();
    boolean allowed = true;
    if (max > 0) {
      final long now = System.nanoTime();
      // ponytail: two threads at the boundary may both reset, so a window can admit one call more
      if (now - windowStart.get() >= WINDOW_NANOS) {
        windowStart.set(now);
        callsInWindow.set(0);
      }
      allowed = callsInWindow.incrementAndGet() <= max;
      if (!allowed) {
        metrics.increment(PLUGIN_ID, METRIC, "skipped");
      }
    }
    return allowed;
  }

  @Override
  public Mono<ControlDecision> decide(final ExecutionEvent event) {
    return settings.events().contains(event.getClass().getSimpleName()) && withinLimit()
        ? model
            .answer(spec, describe(event), Map.of(QUESTION, question))
            .map(answers -> toDecision(event, answers.get(QUESTION)))
            .onErrorResume(
                e -> {
                  log.atWarn().setCause(e).log("Decision model unavailable; no opinion");
                  metrics.increment(PLUGIN_ID, METRIC, "model_error");
                  return Mono.just(ControlDecision.none(Source.MODEL));
                })
        : Mono.just(ControlDecision.none(Source.MODEL));
  }

  /** Plain words work better than JSON for a small model. */
  private static String describe(final ExecutionEvent event) {
    final String text =
        switch (event) {
          case ExecutionEvent.NodeStatusChanged node ->
              "Step "
                  + node.module()
                  + " changed status to "
                  + node.status()
                  + ". Details reported by the step: "
                  + (node.metadata().isEmpty()
                      ? "none"
                      : node.metadata().entrySet().stream()
                          .map(entry -> entry.getKey() + "=" + entry.getValue())
                          .collect(Collectors.joining(", ")))
                  + ".";
          case ExecutionEvent.WorkflowStatusChanged workflow ->
              "The workflow run changed status to " + workflow.status() + ".";
          case ExecutionEvent.LogLine line ->
              "Step " + line.nodeId() + " wrote the " + line.level() + " log line: " + line.line();
          case ExecutionEvent.ControlOutcome outcome ->
              "The command "
                  + outcome.command()
                  + (outcome.applied() ? " was applied" : " was rejected: " + outcome.reason())
                  + (outcome.actor() == null ? "." : ", sent by " + outcome.actor() + ".");
          case ExecutionEvent.EventsMissed missed ->
              missed.count() + " events were missed because the supervisor fell behind.";
          // An input request's prompt and context are written by an agent and are untrusted:
          // they are left out of this prompt until they can be passed as delimited data.
          case ExecutionEvent.InputRequested requested ->
              "Step "
                  + requested.request().nodeId()
                  + " asked for input of kind "
                  + requested.request().spec().kind()
                  + " with risk "
                  + requested.request().spec().risk()
                  + ".";
          case ExecutionEvent.InputEscalated escalated ->
              "The input request of step "
                  + escalated.request().nodeId()
                  + " was handed to the tier "
                  + escalated.request().tier()
                  + ".";
          case ExecutionEvent.InputAnswered answered ->
              "An input request was answered with the decision " + answered.decision() + ".";
          case ExecutionEvent.InputExpired _ -> "An input request expired unanswered.";
          case ExecutionEvent.InputCancelled cancelled ->
              "An input request was cancelled: " + cancelled.reason() + ".";
        };
    return StateText.truncate(text);
  }

  private ControlDecision toDecision(final ExecutionEvent event, final Answer answer) {
    ControlDecision decision = ControlDecision.none(Source.MODEL);
    String outcome = "no_opinion";
    if (answer instanceof final Answer.Choice choice) {
      final Option option = settings.options().get(choice.choice());
      if (option != null && choice.probability() >= settings.minProbability()) {
        decision =
            new ControlDecision(
                command(event.executionId(), option.command()), choice.probability(), Source.MODEL);
        outcome = "decided";
      }
    }
    metrics.increment(PLUGIN_ID, METRIC, outcome);
    return decision;
  }

  private List<ExecutionControlCommand> command(final String executionId, final Command command) {
    return switch (command) {
      case NONE -> List.of();
      case PAUSE -> List.of(new ExecutionControlCommand.PauseWorkflowCommand(executionId));
      case STOP ->
          List.of(
              new ExecutionControlCommand.StopWorkflowCommand(
                  executionId, "Stopped by decision model " + settings.name()));
    };
  }
}
