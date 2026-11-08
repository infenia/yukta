// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What the REST API, the MCP tools and the CLI read of input requests: lists by session, run,
 * workflow and state, one request of a run, its answer, and the check that a request is still open
 * before it is answered or handed up. Every surface uses it, so they agree on order, filters and
 * errors.
 */
@Component
public class InputRequestQueries {

  /** The states listed when the caller names none: the requests that wait for someone. */
  public static final Set<InputState> WAITING =
      Set.copyOf(EnumSet.of(InputState.OPEN, InputState.ESCALATED));

  /** Newest first; the id breaks a tie, so pages are stable. */
  private static final Comparator<InputRequest> NEWEST_FIRST =
      Comparator.comparing(InputRequest::createdAt)
          .reversed()
          .thenComparing(InputRequest::id, Comparator.reverseOrder());

  /** Where the requests are kept. */
  private final InputRequestStore store;

  /**
   * Creates the queries.
   *
   * @param store where the requests are kept
   */
  public InputRequestQueries(final InputRequestStore store) {
    this.store = store;
  }

  /**
   * Reads a comma-separated list of states, such as {@code OPEN,ESCALATED} (case and blanks around
   * the names do not matter).
   *
   * @param names the list, or {@code null} or blank for {@link #WAITING}
   * @return the states
   * @throws IllegalArgumentException naming the valid states, when a name is not one
   */
  public static Set<InputState> states(final @Nullable String names) {
    if (names == null || names.isBlank()) {
      return WAITING;
    }
    final Set<InputState> states = EnumSet.noneOf(InputState.class);
    for (final String raw : names.split(",", -1)) {
      final String name = raw.strip();
      if (!name.isEmpty()) {
        states.add(state(name));
      }
    }
    return states.isEmpty() ? WAITING : Set.copyOf(states);
  }

  private static InputState state(final String name) {
    for (final InputState state : InputState.values()) {
      if (state.name().equals(name.toUpperCase(Locale.ROOT))) {
        return state;
      }
    }
    throw new IllegalArgumentException(
        "Unknown input state: "
            + name
            + ". Valid states: "
            + Arrays.stream(InputState.values())
                .map(InputState::name)
                .collect(Collectors.joining(", ")));
  }

  /**
   * Lists requests, newest first.
   *
   * @param sessionId only this session, or {@code null} for all
   * @param workflowId only this workflow, or {@code null} for all
   * @param executionId only this run, or {@code null} for all
   * @param states only requests in one of these states
   * @return the requests
   */
  public Flux<InputRequest> list(
      final @Nullable String sessionId,
      final @Nullable String workflowId,
      final @Nullable String executionId,
      final Set<InputState> states) {
    final boolean onlyWaiting = states.stream().noneMatch(InputState::isTerminal);
    return (onlyWaiting
            ? store.listOpen(sessionId, executionId)
            : store.list(sessionId, executionId))
        .filter(
            request ->
                states.contains(request.state())
                    && (workflowId == null || workflowId.equals(request.workflowId())))
        .sort(NEWEST_FIRST);
  }

  /**
   * Reads one request of a run.
   *
   * @param executionId the run that asked
   * @param requestId the request
   * @return the request; errors with {@link NotFoundException} when it is unknown or belongs to
   *     another run, so one run's path cannot reach another run's requests
   */
  public Mono<InputRequest> require(final String executionId, final String requestId) {
    return store
        .findById(requestId)
        .filter(request -> request.executionId().equals(executionId))
        .switchIfEmpty(
            Mono.error(() -> new NotFoundException("Input request not found: " + requestId)));
  }

  /**
   * Reads the answer to a request.
   *
   * @param request the request
   * @return the answer; empty when the request is not answered or the answer is no longer kept
   */
  public Mono<InputResponse> answer(final InputRequest request) {
    return request.state() == InputState.ANSWERED ? store.findResponse(request.id()) : Mono.empty();
  }

  /**
   * Reads one request of a run that still waits, before it is answered or handed up.
   *
   * @param executionId the run that asked
   * @param requestId the request
   * @return the request; errors with {@link NotFoundException} as {@link #require} does, {@link
   *     InputAnsweredException} (naming the decision and who gave it) when it is answered, or
   *     {@link ConflictException} when it expired or was cancelled
   */
  public Mono<InputRequest> requireWaiting(final String executionId, final String requestId) {
    return require(executionId, requestId).flatMap(this::stillWaiting);
  }

  private Mono<InputRequest> stillWaiting(final InputRequest request) {
    if (!request.state().isTerminal()) {
      return Mono.just(request);
    }
    if (request.state() != InputState.ANSWERED) {
      return Mono.error(
          new ConflictException("Input request " + request.id() + " is " + request.state()));
    }
    return store
        .findResponse(request.id())
        .map(
            stored ->
                new InputAnsweredException(
                    request.id(),
                    stored.decision(),
                    stored.responder().type() + ":" + stored.responder().id()))
        .defaultIfEmpty(new InputAnsweredException(request.id(), null, null))
        .flatMap(Mono::error);
  }
}
