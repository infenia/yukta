// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.systemone;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model-based control plane: asks a local Ollama System One model (<a
 * href="https://docs.ollama.com/api/systemone">{@code POST /v1/systemone}</a>) whether the
 * execution should {@code continue} or {@code pause} after each workflow or node status change. It
 * never stops an execution; that stays with rules and people. Enabled by setting {@code
 * yukta.control-plane.systemone.model}.
 *
 * <p>It only advises when it is sure: an answer whose probability is below {@code min-probability},
 * a failing or slow server and an unreadable reply all count as no opinion, so the model can never
 * hold up the bus.
 */
@Slf4j
@Component
@ConditionalOnProperty("yukta.control-plane.systemone.model")
public final class SystemOneControlPlane implements ControlPlanePlugin {

  /** Identifies this plugin in the audit log. */
  public static final String PLUGIN_ID = "systemone-control-plane";

  /** Name of the one question asked. */
  private static final String QUESTION = "action";

  /** The answer that leaves the execution alone. */
  private static final String CONTINUE = "continue";

  /** Shared JSON mapper. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The options offered to the model; the descriptions matter, a small model needs them. */
  private static final Map<String, String> CRITERIA =
      Map.of(
          CONTINUE,
          "Normal operation, a success, a retry that may still succeed, or an execution that"
              + " already finished. Nothing to do.",
          "pause",
          "Repeated failures, exhausted retries, authentication errors or resource exhaustion that"
              + " will not fix themselves. A person must look.");

  /** The state is cut to this many characters; System One refuses requests above 64 KiB. */
  private static final int MAX_STATE = 8000;

  /** Client for the Ollama server. */
  private final WebClient client;

  /** The System One model. */
  private final String model;

  /** Answers whose chosen option is less probable than this are ignored. */
  private final double minProbability;

  /** How long to wait for the model. */
  private final Duration timeout;

  /**
   * Creates the plugin.
   *
   * @param builder the web client builder
   * @param baseUrl the Ollama server
   * @param model a local System One model
   * @param minProbability answers whose chosen option is less probable than this are ignored
   * @param timeout how long to wait for the model
   */
  @Autowired
  public SystemOneControlPlane(
      final WebClient.Builder builder,
      @Value("${yukta.control-plane.systemone.base-url:http://localhost:11434}")
          final String baseUrl,
      @Value("${yukta.control-plane.systemone.model}") final String model,
      @Value("${yukta.control-plane.systemone.min-probability:0.6}") final double minProbability,
      @Value("${yukta.control-plane.systemone.timeout:PT2S}") final Duration timeout) {
    this.client = builder.baseUrl(baseUrl).build();
    this.model = model;
    this.minProbability = minProbability;
    this.timeout = timeout;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return PLUGIN_ID;
  }

  @Override
  public Mono<ControlDecision> decide(final ExecutionEvent event) {
    final Mono<ControlDecision> decision;
    final String state = describe(event);
    if (state != null) {
      decision =
          client
              .post()
              .uri("/v1/systemone")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(JSON.writeValueAsString(request(state)))
              .retrieve()
              .bodyToMono(String.class)
              .timeout(timeout)
              .map(body -> toDecision(event, JSON.readTree(body)))
              .onErrorResume(
                  e -> {
                    log.atWarn().setCause(e).log("System One unavailable; no opinion");
                    return Mono.just(ControlDecision.none(Source.MODEL));
                  });
    } else {
      decision = Mono.just(ControlDecision.none(Source.MODEL));
    }
    return decision;
  }

  private Map<String, Object> request(final String state) {
    return Map.of(
        "model",
        model,
        "state",
        state,
        "questions",
        Map.of(
            QUESTION,
            Map.of(
                "type",
                "choice",
                "instructions",
                "What should the person supervising this workflow do about this event?",
                "criteria",
                CRITERIA)));
  }

  /** Plain words work better than JSON for a small model. */
  private static @Nullable String describe(final ExecutionEvent event) {
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
          default -> null;
        };
    return text == null || text.length() <= MAX_STATE ? text : text.substring(0, MAX_STATE);
  }

  private ControlDecision toDecision(final ExecutionEvent event, final JsonNode reply) {
    final JsonNode answer = reply.path("answers").path(QUESTION);
    final String choice = answer.path("choice").asString("");
    final double probability = answer.path("probabilities").path(choice).asDouble(0);
    final List<ExecutionControlCommand> commands =
        "pause".equals(choice) && probability >= minProbability
            ? List.of(new ExecutionControlCommand.PauseWorkflowCommand(event.executionId()))
            : List.of();
    return new ControlDecision(commands, probability, Source.MODEL);
  }
}
