// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.InputAnswerRequest;
import com.infenia.yukta.dto.request.InputEscalateRequest;
import com.infenia.yukta.dto.response.InputRequestView;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InputRequestQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Lists, reads, answers and hands up the input requests of running nodes. Reading needs VIEWER,
 * answering and handing up OPERATOR: the writes live under {@code /api/executions/}, where the
 * roles let OPERATOR control runs and a key limited to sessions reaches only the runs of its
 * sessions. Who may answer a given request is decided by the engine's rules (403 with the rule).
 *
 * <p>The prompt, the context and the advice of a request are written by a workflow or an AI agent:
 * show them as untrusted text.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Input Requests API", description = "List, read, answer and escalate input requests")
public class InputRequestsController {

  /** Execution ID parameter description. */
  private static final String EXECUTION_ID = "Execution ID";

  /** Request ID parameter description. */
  private static final String REQUEST_ID = "Input request ID";

  /** Reads the requests. */
  private final InputRequestQueries queries;

  /** Sends the answers and escalations as control commands. */
  private final ControlBusGateway controlBus;

  /** Lists the input requests of a session. */
  @GetMapping("/sessions/{sessionId}/inputs")
  @Operation(
      summary = "List input requests",
      description =
          "The input requests of the session, newest first, by default those that wait for an"
              + " answer (state=OPEN,ESCALATED). state takes a comma-separated list of OPEN,"
              + " ESCALATED, ANSWERED, EXPIRED and CANCELLED; an unknown state is a 400. Finished"
              + " requests are kept up to yukta.input.memory-max. Paged with limit and cursor"
              + " (X-Next-Cursor header).")
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<ResponseEntity<ApiResponse<List<InputRequestView>>>> listInputs(
      @Parameter(description = "Session ID") @PathVariable final String sessionId,
      @Parameter(description = "Comma-separated states, default OPEN,ESCALATED")
          @RequestParam(required = false)
          final String state,
      @Parameter(description = "Only this workflow") @RequestParam(required = false)
          final String workflowId,
      @Parameter(description = "Only this run") @RequestParam(required = false)
          final String executionId,
      @Parameter(description = "Page size (default 500, at most 1000)")
          @RequestParam(required = false)
          final Integer limit,
      @Parameter(description = "Id of the last request of the previous page")
          @RequestParam(required = false)
          final String cursor) {
    final Set<InputState> states = InputRequestQueries.states(state);
    return queries
        .list(sessionId, workflowId, executionId, states)
        .collectList()
        .flatMap(
            all -> {
              final Pagination.Slice<InputRequest> page =
                  Pagination.slice(all, limit, cursor, InputRequest::id);
              return Flux.fromIterable(page.items())
                  .concatMap(this::view)
                  .collectList()
                  .map(items -> Pagination.respond(items, page.next(), "Input requests retrieved"));
            });
  }

  /** Reads one input request of a run. */
  @GetMapping("/executions/{executionId}/inputs/{requestId}")
  @Operation(
      summary = "Read an input request",
      description =
          "The request with its options, risk, tier, deadline, advice and, once answered, the"
              + " answer. 404 when the run has no such request.")
  public Mono<ApiResponse<InputRequestView>> getInput(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = REQUEST_ID) @PathVariable final String requestId) {
    return queries
        .require(executionId, requestId)
        .flatMap(this::view)
        .map(view -> ApiResponse.success(200, "Input request retrieved", view));
  }

  /** Answers an input request. */
  @PostMapping("/executions/{executionId}/inputs/{requestId}/answer")
  @Operation(
      summary = "Answer an input request",
      description =
          "Answers a request that waits, as a control command (ordered, reviewed and audited like"
              + " the others). 403 when the rules do not let the caller answer it (the message"
              + " names the rule), 409 when it is already answered (with the stored decision and"
              + " responder), expired, cancelled or changed since expectedVersion, 422 when the"
              + " answer does not fit the request, 503 on a standby server.")
  public Mono<ApiResponse<InputRequestView>> answerInput(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = REQUEST_ID) @PathVariable final String requestId,
      @Valid @RequestBody final InputAnswerRequest body) {
    return act(
        executionId,
        requestId,
        "Answer accepted",
        () ->
            controlBus.answerInput(
                executionId,
                requestId,
                body.expectedVersion(),
                body.decision(),
                body.text(),
                body.data(),
                body.rationale(),
                null));
  }

  /** Hands an input request up to the next tier. */
  @PostMapping("/executions/{executionId}/inputs/{requestId}/escalate")
  @Operation(
      summary = "Escalate an input request",
      description =
          "Hands a request that waits up to the next tier with a reason and optional advice,"
              + " as a control command. Errors as for answering.")
  public Mono<ApiResponse<InputRequestView>> escalateInput(
      @Parameter(description = EXECUTION_ID) @PathVariable final String executionId,
      @Parameter(description = REQUEST_ID) @PathVariable final String requestId,
      @Valid @RequestBody final InputEscalateRequest body) {
    return act(
        executionId,
        requestId,
        "Escalation accepted",
        () -> controlBus.escalateInput(executionId, requestId, body.reason(), body.advice()));
  }

  private Mono<ApiResponse<InputRequestView>> act(
      final String executionId,
      final String requestId,
      final String message,
      final Supplier<Mono<Void>> command) {
    return queries
        .requireWaiting(executionId, requestId)
        .then(Mono.defer(command))
        .then(Mono.defer(() -> queries.require(executionId, requestId)))
        .flatMap(this::view)
        .map(view -> ApiResponse.success(200, message, view));
  }

  private Mono<InputRequestView> view(final InputRequest request) {
    return queries
        .answer(request)
        .map(response -> InputRequestView.of(request, response))
        .switchIfEmpty(Mono.fromSupplier(() -> InputRequestView.of(request, null)));
  }
}
