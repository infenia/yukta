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
import com.infenia.yukta.plugin.decision.Answer;
import com.infenia.yukta.plugin.decision.DecisionModel;
import com.infenia.yukta.plugin.decision.ModelSpec;
import com.infenia.yukta.plugin.decision.Question;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
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
 * never hold up the bus. By default it can only pause; a stop has to be configured.
 */
@Slf4j
@Component
@EnableConfigurationProperties(ModelPlaneSettings.class)
@ConditionalOnProperty("yukta.control-plane.model.name")
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class ModelControlPlane implements ControlPlanePlugin {

  /** Identifies this plugin in the audit log. */
  public static final String PLUGIN_ID = "model-control-plane";

  /** Name of the one question asked. */
  private static final String QUESTION = "action";

  /** Asks the model. */
  private final DecisionModel model;

  /** The configuration. */
  private final ModelPlaneSettings settings;

  /** Which model to ask. */
  private final ModelSpec spec;

  /** The question put to the model. */
  private final Question question;

  /**
   * Creates the plugin.
   *
   * @param model asks the model
   * @param settings the configuration
   */
  public ModelControlPlane(final DecisionModel model, final ModelPlaneSettings settings) {
    this.model = model;
    this.settings = settings;
    this.spec =
        new ModelSpec(
            settings.backend(),
            settings.provider(),
            settings.name(),
            settings.baseUrl(),
            settings.apiKey(),
            settings.timeout());
    final Map<String, String> criteria = new LinkedHashMap<>();
    settings.options().forEach((key, option) -> criteria.put(key, option.description()));
    this.question = new Question.Choice(settings.instructions(), criteria);
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return PLUGIN_ID;
  }

  @Override
  public Mono<ControlDecision> decide(final ExecutionEvent event) {
    return settings.events().contains(event.getClass().getSimpleName())
        ? model
            .answer(spec, describe(event), Map.of(QUESTION, question))
            .map(answers -> toDecision(event, answers.get(QUESTION)))
            .onErrorResume(
                e -> {
                  log.atWarn().setCause(e).log("Decision model unavailable; no opinion");
                  return Mono.just(ControlDecision.none(Source.MODEL));
                })
        : Mono.just(ControlDecision.none(Source.MODEL));
  }

  /** Plain words work better than JSON for a small model. */
  private static String describe(final ExecutionEvent event) {
    return switch (event) {
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
          "Step " + line.nodeId() + " wrote the log line: " + line.line();
      case ExecutionEvent.ControlOutcome outcome ->
          "The command "
              + outcome.command()
              + (outcome.applied() ? " was applied" : " was rejected: " + outcome.reason())
              + (outcome.actor() == null ? "." : ", sent by " + outcome.actor() + ".");
    };
  }

  private ControlDecision toDecision(final ExecutionEvent event, final Answer answer) {
    final ControlDecision decision;
    if (answer instanceof final Answer.Choice choice) {
      final Option option = settings.options().get(choice.choice());
      final List<ExecutionControlCommand> commands =
          option == null || choice.probability() < settings.minProbability()
              ? List.of()
              : command(event.executionId(), option.command());
      decision = new ControlDecision(commands, choice.probability(), Source.MODEL);
    } else {
      decision = ControlDecision.none(Source.MODEL);
    }
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
