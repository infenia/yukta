// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.dto.PendingInputs;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InputRequestQueries;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lists, answers and hands up input requests for the MCP tools. Answers and escalations go through
 * the control bus as commands, so the engine's rules decide who may answer, the same as over REST.
 */
@Component
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public class InputProvider {

  /** Most requests one call lists. */
  public static final int MAX_INPUTS = 50;

  /** Longest prompt, context or schema text one entry carries, in characters. */
  public static final int MAX_TEXT = 2000;

  /** Longest advice one entry carries, in characters. */
  public static final int MAX_ADVICE = 1000;

  /** Writes the context and reads answer data. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The data of an answer: a JSON object. */
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

  /** Reads the requests. */
  private final InputRequestQueries queries;

  /** Sends the answers and escalations. */
  private final ControlBusGateway controlBus;

  /**
   * Creates the provider.
   *
   * @param queries reads the requests
   * @param controlBus sends the answers and escalations
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public InputProvider(final InputRequestQueries queries, final ControlBusGateway controlBus) {
    this.queries = queries;
    this.controlBus = controlBus;
  }

  /**
   * Lists input requests, newest first, at most {@link #MAX_INPUTS}.
   *
   * @param sessionId the session, or null or blank for all sessions
   * @param state comma-separated states, or null for OPEN and ESCALATED
   * @return the requests
   */
  public Mono<PendingInputs> list(final @Nullable String sessionId, final @Nullable String state) {
    return Mono.defer(
        () -> {
          final Set<InputState> states = InputRequestQueries.states(state);
          final String session = sessionId == null || sessionId.isBlank() ? null : sessionId;
          return queries
              .list(session, null, null, states)
              .take(MAX_INPUTS + 1L)
              .concatMap(
                  request ->
                      queries
                          .answer(request)
                          .map(InputResponse::decision)
                          .map(decision -> entry(request, decision))
                          .switchIfEmpty(Mono.fromSupplier(() -> entry(request, null))))
              .collectList()
              .map(
                  entries ->
                      new PendingInputs(
                          session,
                          states.stream().sorted().map(InputState::name).toList(),
                          entries.subList(0, Math.min(entries.size(), MAX_INPUTS)),
                          entries.size() > MAX_INPUTS));
        });
  }

  /**
   * Answers an input request of a run of the session.
   *
   * @param sessionId the session that owns the run
   * @param executionId the run that asks
   * @param requestId the request
   * @param decision an option id or a decision such as approve
   * @param text free text, or null
   * @param data a JSON object as text, or null
   * @param rationale why, or null
   * @return what was done
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public Mono<ControlActionResult> answer(
      final String sessionId,
      final String executionId,
      final String requestId,
      final String decision,
      final @Nullable String text,
      final @Nullable String data,
      final @Nullable String rationale) {
    return Mono.defer(
        () -> {
          requireIds(executionId, requestId);
          if (decision == null || decision.isBlank()) {
            return Mono.error(new IllegalArgumentException("decision is required."));
          }
          final Map<String, Object> values = data(data);
          return act(
              sessionId,
              executionId,
              requestId,
              "ANSWER_INPUT",
              "Answered input request " + requestId + " with " + decision + ".",
              () ->
                  controlBus.answerInput(
                      executionId, requestId, null, decision, text, values, rationale, null));
        });
  }

  /**
   * Hands an input request of a run of the session up to the next tier.
   *
   * @param sessionId the session that owns the run
   * @param executionId the run that asks
   * @param requestId the request
   * @param reason why
   * @param advice what the caller suggests to whoever answers next, or null
   * @return what was done
   */
  public Mono<ControlActionResult> escalate(
      final String sessionId,
      final String executionId,
      final String requestId,
      final String reason,
      final @Nullable String advice) {
    return Mono.defer(
        () -> {
          requireIds(executionId, requestId);
          if (reason == null || reason.isBlank()) {
            return Mono.error(new IllegalArgumentException("reason is required."));
          }
          return act(
              sessionId,
              executionId,
              requestId,
              "ESCALATE_INPUT",
              "Escalated input request " + requestId + ".",
              () -> controlBus.escalateInput(executionId, requestId, reason, advice));
        });
  }

  private Mono<ControlActionResult> act(
      final String sessionId,
      final String executionId,
      final String requestId,
      final String action,
      final String message,
      final Supplier<Mono<Void>> command) {
    return ExecutionOwnership.require(controlBus, sessionId, executionId)
        .then(Mono.defer(() -> queries.requireWaiting(executionId, requestId)))
        .flatMap(
            request ->
                command
                    .get()
                    .thenReturn(
                        new ControlActionResult(
                            action, executionId, request.nodeId(), List.of(), message)))
        .onErrorMap(
            InputForbiddenException.class,
            e ->
                new IllegalArgumentException(
                    "Not allowed to answer input request "
                        + requestId
                        + " (rule "
                        + e.rule()
                        + "): "
                        + e.getMessage(),
                    e));
  }

  private static void requireIds(final String executionId, final String requestId) {
    if (executionId == null || executionId.isBlank() || requestId == null || requestId.isBlank()) {
      throw new IllegalArgumentException(
          "executionId and requestId are required. Use list_pending_inputs to find them.");
    }
  }

  private static @Nullable Map<String, Object> data(final @Nullable String data) {
    if (data == null || data.isBlank()) {
      return null;
    }
    try {
      return JSON.readValue(data, OBJECT);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(
          "data must be a JSON object: " + e.getOriginalMessage(), e);
    }
  }

  private static PendingInputs.PendingInput entry(
      final InputRequest request, final @Nullable String decision) {
    final String advice = request.advice();
    final String schema = request.spec().responseSchema();
    return new PendingInputs.PendingInput(
        request.sessionId(),
        request.workflowId(),
        request.executionId(),
        request.id(),
        request.nodeId(),
        request.state().name(),
        request.version(),
        request.spec().kind().name(),
        request.spec().risk().name(),
        cut(request.spec().prompt(), MAX_TEXT),
        cut(JSON.writeValueAsString(request.spec().context()), MAX_TEXT),
        request.spec().options().stream().map(InputProvider::option).toList(),
        schema == null ? null : cut(schema, MAX_TEXT),
        request.tier(),
        advice == null ? null : cut(advice, MAX_ADVICE),
        request.deadline(),
        request.createdAt(),
        decision);
  }

  private static String option(final InputOption option) {
    return option.id().equals(option.label())
        ? option.id()
        : option.id() + " (" + option.label() + ")";
  }

  private static String cut(final String text, final int max) {
    return text.length() <= max ? text : text.substring(0, max) + "…";
  }
}
