// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.filter.AgentCredentialFilter;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorContract;
import com.infenia.yukta.plugin.control.SupervisorResultMapper;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.agent.AgentHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The API of an application that operates a supervisor of type {@code http}. It is reached with the
 * credential of that supervisor only ({@code AgentCredentialFilter}); everything it sends is a
 * directive that the supervisor checks like any engine's.
 */
@RestController
@RequestMapping(AgentCredentialFilter.PATH)
@RequiredArgsConstructor
@Tag(
    name = "Agent API",
    description = "For an application that is the decision maker of a supervisor")
public class AgentApiController {

  private static final int MAX_POLL_SECONDS = 25;
  private static final int MAX_BATCH = 50;
  private static final long POLL_WINDOW_MILLIS = 200;

  private static final Set<String> VERDICTS = Set.of("ALLOW", "DENY", "REQUIRE_APPROVAL");

  private final AgentHub hub;
  private final SupervisorHost host;

  /**
   * A signal on the stream.
   *
   * @param cursor where the stream is; resume with {@code after=<cursor>}
   * @param kind the signal kind
   * @param signal the signal
   */
  public record SignalEnvelope(long cursor, String kind, Signal signal) {}

  /**
   * Who the credential is.
   *
   * @param supervisor the supervisor id
   * @param contract the contract version
   * @param connected whether the supervisor is running an {@code http} engine
   */
  public record SelfView(String supervisor, int contract, boolean connected) {}

  /**
   * A directive from the agent.
   *
   * @param key an idempotency key; a repeat is applied once
   * @param cause {@code event:<id>}, {@code answers:<id>}, {@code tick} or {@code initiative}
   * @param reason why; required for a command nobody prompted
   * @param ifStatus only if the run is in this status
   * @param command the command: {@code type}, {@code executionId} (or {@code target}: {@code
   *     execution:<id>}, {@code workflow:<id>}, {@code owned.running} or {@code held}, at most 200
   *     executions, each applied and audited alone) and its own fields
   */
  public record DirectiveBody(
      String key,
      @Nullable String cause,
      @Nullable String reason,
      @Nullable String ifStatus,
      Map<String, Object> command) {}

  /**
   * The answer to a review.
   *
   * @param verdict {@code ALLOW}, {@code DENY}, {@code REQUIRE_APPROVAL} or {@code NO_OPINION}
   * @param reason why
   */
  public record VerdictBody(String verdict, @Nullable String reason) {}

  /**
   * The answer to a delegation.
   *
   * @param answer the answer
   */
  public record AnswerBody(Map<String, Object> answer) {}

  /**
   * Who the credential is.
   *
   * @param exchange the request
   * @return the supervisor
   */
  @GetMapping("/self")
  @Operation(summary = "Who the agent credential is")
  public Mono<ApiResponse<SelfView>> self(final ServerWebExchange exchange) {
    final String id = supervisor(exchange);
    return Mono.just(
        ApiResponse.success(
            200,
            "Agent",
            new SelfView(id, SupervisorContract.VERSION, hub.channel(id).isPresent())));
  }

  /**
   * The signals of the supervisor as they happen.
   *
   * @param exchange the request
   * @return a server-sent event stream; best effort, so read the executions to catch up
   */
  @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(summary = "Stream the signals of the supervisor")
  public Flux<ServerSentEvent<SignalEnvelope>> events(
      final ServerWebExchange exchange, @RequestParam(defaultValue = "-1") final long after) {
    final String id = supervisor(exchange);
    final AgentHub.Channel channel = channel(id);
    final Flux<SignalEnvelope> envelopes =
        channel
            .signalsAfter(after < 0 ? channel.cursor() : after)
            .map(s -> new SignalEnvelope(s.cursor(), s.signal().kind().name(), s.signal()))
            // a revoked or rotated credential ends the stream at once
            .takeUntilOther(hub.credentialChanges().filter(id::equals));
    return SseStreams.events(envelopes, null, SseStreams.HEARTBEAT);
  }

  /**
   * The signals after a cursor, waiting up to 25 seconds for the first one: a poll, for a client
   * that cannot hold a stream open (an AI agent over MCP).
   *
   * @param exchange the request
   * @param after the cursor of the last signal seen
   * @param waitSeconds how long to wait when there is none yet (at most 25)
   * @return the signals that were there, at most 50, and the cursor to continue from
   */
  @GetMapping("/signals")
  @Operation(summary = "Poll the signals after a cursor")
  public Mono<ApiResponse<SignalBatch>> next(
      final ServerWebExchange exchange,
      @RequestParam(defaultValue = "0") final long after,
      @RequestParam(name = "wait", defaultValue = "0") final int waitSeconds) {
    final Duration wait = Duration.ofSeconds(Math.clamp(waitSeconds, 0, MAX_POLL_SECONDS));
    return channel(supervisor(exchange))
        .signalsAfter(after)
        .map(s -> new SignalEnvelope(s.cursor(), s.signal().kind().name(), s.signal()))
        .buffer(Duration.ofMillis(POLL_WINDOW_MILLIS))
        .filter(batch -> !batch.isEmpty())
        .next()
        .timeout(wait, Mono.just(List.of()))
        .map(
            batch -> {
              final List<SignalEnvelope> some = batch.stream().limit(MAX_BATCH).toList();
              final long cursor = some.isEmpty() ? after : some.get(some.size() - 1).cursor();
              return ApiResponse.success(200, "Signals", new SignalBatch(cursor, some));
            });
  }

  /**
   * A batch of signals.
   *
   * @param cursor where to continue from
   * @param signals the signals, oldest first; empty when nothing happened in time
   */
  public record SignalBatch(long cursor, List<SignalEnvelope> signals) {}

  /**
   * The owned state with a cursor: read it, then stream {@code events?after=<cursor>} and nothing
   * is missed that is still remembered (the newest {@value AgentHub#REMEMBERED} signals).
   *
   * @param exchange the request
   * @return the cursor, the executions and the open input requests
   */
  @GetMapping("/snapshot")
  @Operation(summary = "The owned state and a cursor to resume the stream from")
  public Mono<ApiResponse<SnapshotView>> snapshot(final ServerWebExchange exchange) {
    final AgentHub.Channel channel = channel(supervisor(exchange));
    final long cursor = channel.cursor();
    return Mono.zip(
            channel.reads().executions(null, SupervisorContract.SELECTOR_CAP).collectList(),
            channel.reads().openInputs(SupervisorContract.SELECTOR_CAP).collectList())
        .map(
            both ->
                ApiResponse.success(
                    200, "Snapshot", new SnapshotView(cursor, both.getT1(), both.getT2())));
  }

  /**
   * The owned state at a cursor.
   *
   * @param cursor where the stream was when it was read
   * @param executions the owned executions
   * @param inputs the open input requests
   */
  public record SnapshotView(
      long cursor, List<EngineReads.RunView> executions, List<InputRequest> inputs) {}

  /**
   * The executions the supervisor owns.
   *
   * @param exchange the request
   * @param status only this status
   * @param limit most rows (at most 200)
   * @return the executions, newest first
   */
  @GetMapping("/executions")
  @Operation(summary = "List the owned executions")
  public Mono<ApiResponse<List<EngineReads.RunView>>> executions(
      final ServerWebExchange exchange,
      @RequestParam(required = false) final String status,
      @RequestParam(defaultValue = "50") final int limit) {
    return channel(supervisor(exchange))
        .reads()
        .executions(status, limit)
        .collectList()
        .map(rows -> ApiResponse.success(200, "Executions", rows));
  }

  /**
   * The open input requests of the owned units.
   *
   * @param exchange the request
   * @param limit most rows (at most 200)
   * @return the requests
   */
  @GetMapping("/inputs")
  @Operation(summary = "List the open input requests")
  public Mono<ApiResponse<List<InputRequest>>> inputs(
      final ServerWebExchange exchange, @RequestParam(defaultValue = "50") final int limit) {
    return channel(supervisor(exchange))
        .reads()
        .openInputs(limit)
        .collectList()
        .map(rows -> ApiResponse.success(200, "Input requests", rows));
  }

  /**
   * Sends a command. The supervisor checks it (ownership, allowed commands, reason and initiative
   * for a command nobody prompted, rate, dry run) before it has any effect.
   *
   * @param exchange the request
   * @param body the directive
   * @return accepted when handed to the supervisor, which does not mean it was applied
   */
  @PostMapping("/directives")
  @Operation(summary = "Send a command")
  public Mono<ApiResponse<Void>> directive(
      final ServerWebExchange exchange, @RequestBody final DirectiveBody body) {
    final String supervisor = supervisor(exchange);
    final AgentHub.Channel channel = channel(supervisor);
    if (body.key() == null || body.key().isBlank() || body.command() == null) {
      throw new IllegalArgumentException("A directive needs a key and a command");
    }
    final Cause cause = cause(body.cause());
    if (!cause.unprompted() && !channel.sent(java.util.Objects.requireNonNull(cause.id()))) {
      // the server, not the agent, says what a directive reacts to: an unknown id is initiative
      // in disguise and would skip the initiative list, the reason and its rate limit
      throw new IllegalArgumentException(
          "cause " + cause.text() + " names no signal sent to this supervisor");
    }
    final Map<String, Object> raw = body.command();
    if (raw.get("executionId") == null && raw.get("target") != null) {
      return expand(supervisor, channel, raw.get("target").toString())
          .map(
              ids -> {
                for (final String id : ids) {
                  final Map<String, Object> one = new HashMap<>(raw);
                  one.remove("target");
                  one.put("executionId", id);
                  offer(
                      channel,
                      new Directive.Command(
                          body.key() + "#" + id,
                          command(one),
                          cause,
                          body.reason(),
                          body.ifStatus()));
                }
                return ApiResponse.<Void>success(
                    202,
                    "Directive accepted for "
                        + ids.size()
                        + " executions"
                        + (ids.size() >= SupervisorContract.SELECTOR_CAP
                            ? " (the most allowed)"
                            : ""),
                    null);
              });
    }
    offer(
        channel,
        new Directive.Command(body.key(), command(raw), cause, body.reason(), body.ifStatus()));
    return Mono.just(ApiResponse.success(202, "Directive accepted", null));
  }

  private static void offer(final AgentHub.Channel channel, final Directive.Command directive) {
    if (!channel.offer(directive)) {
      throw new ConflictException("The supervisor is not taking directives");
    }
  }

  /** The executions a selector names, at most {@link SupervisorContract#SELECTOR_CAP}. */
  private Mono<List<String>> expand(
      final String supervisor, final AgentHub.Channel channel, final String selector) {
    final int cap = SupervisorContract.SELECTOR_CAP;
    if (selector.startsWith("execution:") && selector.length() > "execution:".length()) {
      return Mono.just(List.of(selector.substring("execution:".length())));
    }
    if ("held".equals(selector)) {
      return Mono.just(host.held(supervisor).stream().limit(cap).toList());
    }
    if ("owned.running".equals(selector)) {
      return channel
          .reads()
          .executions("RUNNING", cap)
          .map(EngineReads.RunView::executionId)
          .collectList();
    }
    if (selector.startsWith("workflow:") && selector.length() > "workflow:".length()) {
      final String workflow = selector.substring("workflow:".length());
      return channel
          .reads()
          .executions(null, cap)
          .filter(run -> workflow.equals(run.workflowId()))
          .map(EngineReads.RunView::executionId)
          .collectList();
    }
    throw new IllegalArgumentException(
        "target must be execution:<id>, workflow:<id>, owned.running or held");
  }

  /**
   * Answers a review.
   *
   * @param exchange the request
   * @param id the review id
   * @param body the verdict
   * @return accepted
   */
  @PostMapping("/reviews/{id}")
  @Operation(summary = "Answer a review")
  public Mono<ApiResponse<Void>> review(
      final ServerWebExchange exchange,
      @PathVariable final String id,
      @RequestBody final VerdictBody body) {
    final AgentHub.Channel channel = channel(supervisor(exchange));
    final String verdict =
        body.verdict() == null ? "" : body.verdict().toUpperCase(java.util.Locale.ROOT);
    final Directive directive;
    if ("NO_OPINION".equals(verdict)) {
      directive = new Directive.NoOpinion(id);
    } else if (VERDICTS.contains(verdict)) {
      directive =
          new Directive.ReviewVerdict(id, Directive.Verdict.valueOf(verdict), body.reason());
    } else {
      throw new IllegalArgumentException(
          "verdict must be ALLOW, DENY, REQUIRE_APPROVAL or NO_OPINION");
    }
    channel.offer(directive);
    return Mono.just(ApiResponse.success(202, "Verdict accepted", null));
  }

  /**
   * Answers a delegation.
   *
   * @param exchange the request
   * @param id the delegation id
   * @param body the answer
   * @return accepted
   */
  @PostMapping("/delegations/{id}")
  @Operation(summary = "Answer a delegation")
  public Mono<ApiResponse<Void>> delegation(
      final ServerWebExchange exchange,
      @PathVariable final String id,
      @RequestBody final AnswerBody body) {
    channel(supervisor(exchange))
        .offer(
            new Directive.DelegationAnswer(id, body.answer() == null ? Map.of() : body.answer()));
    return Mono.just(ApiResponse.success(202, "Answer accepted", null));
  }

  private AgentHub.Channel channel(final String supervisor) {
    return hub.channel(supervisor)
        .orElseThrow(
            () ->
                new NotFoundException(
                    "Supervisor " + supervisor + " is not running an http engine"));
  }

  private static String supervisor(final ServerWebExchange exchange) {
    final Object id = exchange.getAttribute(AgentCredentialFilter.SUPERVISOR_ATTRIBUTE);
    if (id == null) {
      throw new IllegalStateException("The agent filter did not run");
    }
    return id.toString();
  }

  private static ExecutionControlCommand command(final Map<String, Object> raw) {
    final Map<String, Object> copy = new HashMap<>(raw);
    if (!(copy.get("executionId") instanceof String run) || run.isBlank()) {
      throw new IllegalArgumentException("A command needs an executionId");
    }
    final List<ExecutionControlCommand> mapped =
        SupervisorResultMapper.map(
                List.of(Map.of("commands", List.of(copy))),
                Set.copyOf(SupervisorCapabilities.commandNames()),
                run,
                1)
            .commands();
    if (mapped.isEmpty()) {
      throw new IllegalArgumentException("Not a command this API can send: " + raw.get("type"));
    }
    return mapped.get(0);
  }

  private static Cause cause(final @Nullable String text) {
    if (text == null || "initiative".equals(text)) {
      return Cause.initiative();
    }
    if ("tick".equals(text)) {
      return Cause.tick();
    }
    if (text.startsWith("event:")) {
      return Cause.event(text.substring("event:".length()));
    }
    if (text.startsWith("answers:")) {
      return Cause.answers(text.substring("answers:".length()));
    }
    throw new IllegalArgumentException(
        "cause must be event:<id>, answers:<id>, tick or initiative");
  }
}
