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
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
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
 * execution should {@code continue}, {@code pause} or {@code stop} after each workflow or node
 * status change. Enabled by setting {@code yukta.control-plane.systemone.model}.
 *
 * <p>It only advises when it is sure: an answer below {@code min-confidence}, a failing or slow
 * server and an unreadable reply all count as no opinion, so the model can never hold up the bus.
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

  /** The options offered to the model. */
  private static final Map<String, String> CRITERIA =
      Map.of(
          CONTINUE,
          "Healthy or recoverable; leave the execution alone",
          "pause",
          "Something is going wrong; hold the execution so a person can look",
          "stop",
          "The execution cannot succeed; drain and stop it");

  /** Client for the Ollama server. */
  private final WebClient client;

  /** The System One model. */
  private final String model;

  /** Answers below this confidence are ignored. */
  private final double minConfidence;

  /** How long to wait for the model. */
  private final Duration timeout;

  /**
   * Creates the plugin.
   *
   * @param builder the web client builder
   * @param baseUrl the Ollama server
   * @param model a local System One model
   * @param minConfidence answers below this confidence are ignored
   * @param timeout how long to wait for the model
   */
  @Autowired
  public SystemOneControlPlane(
      final WebClient.Builder builder,
      @Value("${yukta.control-plane.systemone.base-url:http://localhost:11434}")
          final String baseUrl,
      @Value("${yukta.control-plane.systemone.model}") final String model,
      @Value("${yukta.control-plane.systemone.min-confidence:0.8}") final double minConfidence,
      @Value("${yukta.control-plane.systemone.timeout:PT2S}") final Duration timeout) {
    this.client = builder.baseUrl(baseUrl).build();
    this.model = model;
    this.minConfidence = minConfidence;
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
    if (event instanceof ExecutionEvent.NodeStatusChanged
        || event instanceof ExecutionEvent.WorkflowStatusChanged) {
      decision =
          client
              .post()
              .uri("/v1/systemone")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(JSON.writeValueAsString(request(event)))
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

  private Map<String, Object> request(final ExecutionEvent event) {
    final Map<String, Object> state =
        new ConcurrentHashMap<>(JSON.convertValue(event, new MapType()));
    state.put("event", event.getClass().getSimpleName());
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
                "A workflow engine reports this event. What should its supervisor do?",
                "criteria",
                CRITERIA)));
  }

  private ControlDecision toDecision(final ExecutionEvent event, final JsonNode reply) {
    final JsonNode answer = reply.path("answers").path(QUESTION);
    final double confidence = answer.path("confidence").asDouble(0);
    final String choice = answer.path("choice").asString("");
    final String executionId = event.executionId();
    final List<ExecutionControlCommand> commands;
    if (confidence < minConfidence) {
      commands = List.of();
    } else {
      commands =
          switch (choice) {
            case "pause" -> List.of(new ExecutionControlCommand.PauseWorkflowCommand(executionId));
            case "stop" ->
                List.of(
                    new ExecutionControlCommand.StopWorkflowCommand(
                        executionId, "Stopped by System One model " + model));
            default -> List.of();
          };
    }
    return new ControlDecision(commands, confidence, Source.MODEL);
  }

  /** Type token for a JSON object. */
  private static final class MapType
      extends tools.jackson.core.type.TypeReference<Map<String, Object>> {}
}
