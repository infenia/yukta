// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputAsk;
import com.infenia.yukta.plugin.input.InputCancelledException;
import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputLimitException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.input.Vote;
import com.infenia.yukta.service.input.route.FinalAction;
import com.infenia.yukta.service.input.route.InputRoute;
import com.infenia.yukta.service.input.route.Mode;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.route.Tier;
import com.infenia.yukta.service.input.route.TierTargets;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

/**
 * Owns an input request from the moment a plugin asks until it is answered, expired or cancelled.
 *
 * <p>It stores the request, publishes events, enforces the limits and completes the {@code Mono}
 * the asker waits on. Supervisors never wait for a person: the waiting is done here. Every change
 * of a request is a compare-and-set on its version, so two changes at once produce one winner, and
 * the loser gets the current state back (idempotent for callers). A terminal request never changes.
 *
 * <p>This is the in-memory broker: answers and authorisation are the commands of a later phase,
 * which call {@link #answer} and {@link #escalate}; tiers and routes come after that.
 */
@Slf4j
@Service
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.GodClass",
  "PMD.CyclomaticComplexity",
  "PMD.UseConcurrentHashMap"
})
public class InputBroker {

  /** The tier every request is in until routes exist. */
  public static final String DEFAULT_TIER = "default";

  /** The step from one tier to the next. */
  private static final int NEXT = 1;

  /** The number of different decisions when every voter agrees. */
  private static final int SINGLE_DECISION = 1;

  /** Key of the request context that names the signal a request stands for. */
  public static final String SIGNAL_CONTEXT_KEY = InputAsk.SIGNAL_CONTEXT_KEY;

  /** Counter of requests by kind, risk and what became of them. */
  private static final String REQUESTS_METRIC = "yukta.input.requests";

  /** Counter of refused asks by rule. */
  private static final String DENIED_METRIC = "yukta.input.denied";

  /** Timer from ask to the end of a request. */
  private static final String WAIT_METRIC = "yukta.input.wait";

  /** Log key of the request id. */
  private static final String LOG_REQUEST_ID = "requestId";

  /** Log key of the run id. */
  private static final String LOG_EXECUTION_ID = "executionId";

  /** Log key of the node id. */
  private static final String LOG_NODE_ID = "nodeId";

  /** Payload key of the request id in an execution log entry. */
  private static final String PAYLOAD_REQUEST_ID = "requestId";

  /** Payload key of the tier. */
  private static final String PAYLOAD_TIER = "tier";

  /** Payload key of the reason. */
  private static final String PAYLOAD_REASON = "reason";

  /** Payload key of the risk of the request. */
  private static final String PAYLOAD_RISK = "risk";

  /** Payload keys of who answered or handed a request up. */
  private static final String PAYLOAD_RESPONDER_TYPE = "responderType";

  private static final String PAYLOAD_RESPONDER_ID = "responderId";

  /** Longest rationale or reason the log keeps, in characters. */
  private static final int LOG_TEXT_MAX = 500;

  /** Where requests are kept. */
  private final InputRequestStore store;

  /** Receives the events of requests. */
  private final ExecutionEventFeed feed;

  /** Records what happened to requests under their run. */
  private final ExecutionLogStore executionLog;

  /** Registry for the metrics. */
  private final MeterRegistry meters;

  /** Removes secrets from the prompt and the context. */
  private final InputRedactor redactor;

  /** The limits. */
  private final YuktaProperties.Input limits;

  /** The time, replaceable in tests. */
  private final Clock clock;

  /** Picks the route of a request. */
  private final RouteResolver routes;

  /** The supervisors that are attached now. */
  private final ObjectProvider<EligibleSupervisors> supervisors;

  /** Askers waiting for a request, by request id. */
  private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

  /** The reason a request was cancelled, until its waiting callers have been told. */
  private final Map<String, String> cancelReasons = new ConcurrentHashMap<>();

  /** Requests that are open or escalated now; the value of the open gauge. */
  private final AtomicInteger open = new AtomicInteger();

  /**
   * Creates the broker.
   *
   * @param store where requests are kept
   * @param feed receives the events
   * @param executionLog records what happened to requests
   * @param meters registry for the metrics
   * @param redactor removes secrets from prompt and context
   * @param properties the {@code yukta.input} settings
   * @param routes picks the route of a request
   * @param supervisors the attached supervisors, if there are any
   */
  @Autowired
  public InputBroker(
      final InputRequestStore store,
      final ExecutionEventFeed feed,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final InputRedactor redactor,
      final YuktaProperties properties,
      final RouteResolver routes,
      final ObjectProvider<EligibleSupervisors> supervisors) {
    this(
        store,
        feed,
        executionLog,
        meters,
        redactor,
        properties.input(),
        Clock.systemUTC(),
        routes,
        supervisors);
  }

  /**
   * Creates the broker with explicit limits and clock.
   *
   * @param store where requests are kept
   * @param feed receives the events
   * @param executionLog records what happened to requests
   * @param meters registry for the metrics
   * @param redactor removes secrets from prompt and context
   * @param limits the limits
   * @param clock the time
   */
  public InputBroker(
      final InputRequestStore store,
      final ExecutionEventFeed feed,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final InputRedactor redactor,
      final YuktaProperties.Input limits,
      final Clock clock) {
    this(
        store,
        feed,
        executionLog,
        meters,
        redactor,
        limits,
        clock,
        new RouteResolver(Map.of(), null),
        null);
  }

  /**
   * Creates the broker with explicit limits, clock, routes and supervisors.
   *
   * @param store where requests are kept
   * @param feed receives the events
   * @param executionLog records what happened to requests
   * @param meters registry for the metrics
   * @param redactor removes secrets from prompt and context
   * @param limits the limits
   * @param clock the time
   * @param routes picks the route of a request
   * @param supervisors the attached supervisors, or {@code null} for none
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public InputBroker(
      final InputRequestStore store,
      final ExecutionEventFeed feed,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final InputRedactor redactor,
      final YuktaProperties.Input limits,
      final Clock clock,
      final RouteResolver routes,
      final @Nullable ObjectProvider<EligibleSupervisors> supervisors) {
    this.routes = routes;
    this.supervisors = supervisors;
    this.store = store;
    this.feed = feed;
    this.executionLog = executionLog;
    this.meters = meters;
    this.redactor = redactor;
    this.limits = limits;
    this.clock = clock;
    meters.gauge("yukta.input.open", open);
  }

  /**
   * Asks and waits. The identity comes from the engine, never from the plugin.
   *
   * <p>An earlier answer to the same key is returned at once (a run that asks again after a
   * restart). An open request with the same key is shared: every caller gets the same result.
   * Cancelling the last waiting caller cancels the request.
   *
   * @param who the run and node that ask
   * @param raw the question; its prompt and context are redacted before anything is stored
   * @return the answer; fails with {@link InputLimitException}, {@link InputExpiredException} or
   *     {@link InputCancelledException}
   */
  public Mono<InputResponse> ask(final InputIdentity who, final InputRequestSpec raw) {
    return Mono.defer(
        () -> {
          final InputRequestSpec spec = redactor.redact(raw);
          return store
              .findAnsweredByKey(spec.key())
              .flatMap(answered -> store.findResponse(answered.id()))
              .switchIfEmpty(Mono.defer(() -> openRequest(who, spec).flatMap(this::await)));
        });
  }

  /**
   * Raises a request for the server itself and does not wait: the entry point of a control command
   * that a review parked, next to {@link #ask} for plugins. The identity is bound by the caller
   * from the command and its run, never from a Reactor context. An open request with the same key
   * is returned instead of a second one; an earlier answer is not reused, so a command sent again
   * after its request ended asks again.
   *
   * @param who the run (and node, or a stand-in for a command on the whole run) the request is for
   * @param raw the question; its prompt and context are redacted before anything is stored
   * @return the request, new or the open one with the same key; fails with {@link
   *     InputLimitException} when a cap is reached
   */
  public Mono<InputRequest> raise(final InputIdentity who, final InputRequestSpec raw) {
    return Mono.defer(() -> openRequest(who, redactor.redact(raw)));
  }

  /**
   * How a request ends, for a caller of {@link #raise}. Like a caller of {@link #ask}, the last
   * subscriber that cancels cancels the request.
   *
   * @param request the request
   * @return the answer; fails with {@link InputExpiredException} or {@link InputCancelledException}
   */
  public Mono<InputResponse> outcome(final InputRequest request) {
    return await(request);
  }

  /**
   * Answers a request. Called by the answer command, after it has authorised the caller.
   *
   * @param requestId the request
   * @param expectedVersion the version the caller saw, or {@code null} to answer whatever the
   *     version is; a version that moved leaves the request as it is
   * @param response the answer
   * @return the request: answered if this call won, otherwise as it is now (an earlier answer, an
   *     expiry or a cancellation); empty if there is no such request
   */
  public Mono<InputRequest> answer(
      final String requestId, final @Nullable Long expectedVersion, final InputResponse response) {
    if (!requestId.equals(response.requestId())) {
      return Mono.error(
          new IllegalArgumentException("The response is for another request than " + requestId));
    }
    if (response.responder().type() != Responder.Type.SUPERVISOR) {
      return finalAnswer(requestId, expectedVersion, response);
    }
    return store
        .findById(requestId)
        .filter(current -> isLive(current.state()))
        .flatMap(
            current ->
                routes
                    .resolve(current.spec(), current.sessionId())
                    .flatMap(route -> supervisorAnswer(current, route, expectedVersion, response)))
        .switchIfEmpty(Mono.defer(() -> finalAnswer(requestId, expectedVersion, response)));
  }

  /** Ends a request with an answer; a person's answer, a decided vote or a default. */
  private Mono<InputRequest> finalAnswer(
      final String requestId, final @Nullable Long expectedVersion, final InputResponse response) {
    return transition(
            requestId,
            expectedVersion,
            InputBroker::isLive,
            current -> advance(current, InputState.ANSWERED, current.advice()))
        .flatMap(
            change ->
                change.applied()
                    ? store.saveResponse(response).map(saved -> answered(change.request(), saved))
                    : Mono.just(change.request()));
  }

  /**
   * An answer from a supervisor: too unsure to count, a vote, or the answer.
   *
   * <p>An answer below the tier's {@code minConfidence} (no confidence counts as zero) is advice
   * for the next tier. In a tier that votes, the answer is a vote; a person's answer never is.
   */
  private Mono<InputRequest> supervisorAnswer(
      final InputRequest current,
      final InputRoute route,
      final @Nullable Long expectedVersion,
      final InputResponse response) {
    final Tier tier = tierOf(route, current.tier());
    if (tier == null) {
      return finalAnswer(current.id(), expectedVersion, response);
    }
    final Double reported = response.confidence();
    final double confidence = reported == null ? 0 : reported;
    if (tier.minConfidence() > 0 && confidence < tier.minConfidence()) {
      final Responder by = response.responder();
      final String advice =
          truncated(
              redactor.text(
                  "supervisor "
                      + by.id()
                      + " answered "
                      + response.decision()
                      + (response.rationale() == null ? "" : ": " + response.rationale())));
      return handUp(current, route, "low-confidence-answer", advice, by);
    }
    return tier.votes()
        ? vote(current, route, tier, expectedVersion, response)
        : finalAnswer(current.id(), expectedVersion, response);
  }

  /** Moves a request up, or flags it as handed up when its route has no higher tier. */
  private Mono<InputRequest> handUp(
      final InputRequest current,
      final InputRoute route,
      final String reason,
      final @Nullable String advice,
      final @Nullable Responder by) {
    return moveUp(current, route, reason, advice, by)
        .switchIfEmpty(Mono.defer(() -> flag(current.id(), reason, advice, by)));
  }

  private Mono<InputRequest> vote(
      final InputRequest current,
      final InputRoute route,
      final Tier tier,
      final @Nullable Long expectedVersion,
      final InputResponse response) {
    final Responder voter = response.responder();
    if (current.votes().stream().anyMatch(v -> v.voter().equals(voter.id()))) {
      // a supervisor votes once; a second vote changes nothing
      return Mono.just(current);
    }
    final Vote vote =
        new Vote(voter.id(), response.decision(), response.confidence(), clock.instant());
    return transition(
            current.id(),
            expectedVersion,
            InputBroker::isLive,
            c ->
                replace(
                    c,
                    c.state(),
                    c.tier(),
                    c.targets(),
                    c.advice(),
                    c.tierDeadline(),
                    withVote(c.votes(), vote)))
        .flatMap(
            change ->
                change.applied()
                    ? decide(change.request(), route, tier, response)
                    : Mono.just(change.request()));
  }

  private static List<Vote> withVote(final List<Vote> votes, final Vote vote) {
    if (votes.stream().anyMatch(v -> v.voter().equals(vote.voter()))) {
      return votes;
    }
    final List<Vote> all = new java.util.ArrayList<>(votes);
    all.add(vote);
    return all;
  }

  /** Counts the votes: resolves when enough agree, hands up on a split, otherwise waits on. */
  private Mono<InputRequest> decide(
      final InputRequest request,
      final InputRoute route,
      final Tier tier,
      final InputResponse last) {
    final List<Vote> votes = request.votes();
    final int eligible = TierTargets.supervisors(request, request.targets(), directory()).size();
    final Map<String, Long> counts = new LinkedHashMap<>();
    votes.forEach(v -> counts.merge(v.decision(), 1L, Long::sum));
    if (tier.mode() == Mode.QUORUM) {
      final String winner =
          counts.entrySet().stream()
              .filter(e -> e.getValue() >= tier.quorum())
              .max(Map.Entry.comparingByValue())
              .map(Map.Entry::getKey)
              .orElse(null);
      if (winner != null) {
        return resolved(request, winner, last);
      }
      return votes.size() >= eligible
          ? handUp(request, route, "split-vote", null, null)
          : Mono.just(request);
    }
    if (counts.size() > SINGLE_DECISION) {
      return handUp(request, route, "split-vote", null, null);
    }
    return votes.size() >= eligible
        ? resolved(request, votes.getFirst().decision(), last)
        : Mono.just(request);
  }

  /** Ends a request with the decision its voters reached; the rationale names them. */
  private Mono<InputRequest> resolved(
      final InputRequest request, final String decision, final InputResponse last) {
    final String voters =
        String.join(
            ", ",
            request.votes().stream()
                .filter(v -> v.decision().equals(decision))
                .map(Vote::voter)
                .toList());
    final InputResponse response =
        new InputResponse(
            request.id(),
            decision,
            last.text(),
            last.data(),
            last.responder(),
            truncated(
                "voted by " + voters + (last.rationale() == null ? "" : "; " + last.rationale())),
            last.confidence(),
            clock.instant());
    return finalAnswer(request.id(), null, response);
  }

  /**
   * Hands a request up. In this phase it only records the advice and moves an open request to
   * {@code ESCALATED}; tiers come with routes.
   *
   * @param requestId the request
   * @param reason why, for the event and the log
   * @param advice text for whoever answers next, or {@code null}
   * @return the request: escalated if this call won, otherwise as it is now; empty if unknown
   */
  public Mono<InputRequest> escalate(
      final String requestId, final String reason, final @Nullable String advice) {
    return escalate(requestId, reason, advice, null);
  }

  /**
   * Hands a request up on behalf of a sender, who is recorded in the log.
   *
   * @param requestId the request
   * @param reason why, for the event and the log
   * @param advice text for whoever answers next, or {@code null}
   * @param by who hands it up, or {@code null} when the system does
   * @return the request: escalated if this call won, otherwise as it is now; empty if unknown
   */
  public Mono<InputRequest> escalate(
      final String requestId,
      final String reason,
      final @Nullable String advice,
      final @Nullable Responder by) {
    final String cleanReason = redactor.text(reason);
    final @Nullable String cleanAdvice = advice == null ? null : redactor.text(advice);
    return store
        .findById(requestId)
        .flatMap(
            current ->
                isLive(current.state())
                    ? routes
                        .resolve(current.spec(), current.sessionId())
                        .flatMap(
                            route ->
                                moveUp(current, route, cleanReason, cleanAdvice, by)
                                    .switchIfEmpty(
                                        Mono.defer(
                                            () -> flag(requestId, cleanReason, cleanAdvice, by))))
                    : Mono.just(current));
  }

  /** Marks an open request as handed up when there is no higher tier to hand it to. */
  private Mono<InputRequest> flag(
      final String requestId,
      final String reason,
      final @Nullable String advice,
      final @Nullable Responder by) {
    return transition(
            requestId,
            null,
            state -> state == InputState.OPEN,
            current -> advance(current, InputState.ESCALATED, advice))
        .doOnNext(
            change -> {
              if (change.applied()) {
                escalated(change.request(), reason, by);
              }
            })
        .map(Change::request);
  }

  /**
   * Moves a live request to the next tier of its route.
   *
   * @return the request in its new tier; the request as it is if another call moved it first; empty
   *     if the route has no tier above
   */
  private Mono<InputRequest> moveUp(
      final InputRequest current,
      final InputRoute route,
      final String reason,
      final @Nullable String advice,
      final @Nullable Responder by) {
    final int at = indexOf(route, current.tier());
    if (at < 0 || at + NEXT >= route.tiers().size()) {
      return Mono.empty();
    }
    final Tier next = route.tiers().get(at + NEXT);
    final List<SupervisorScope> references = references(current, by);
    return transition(
            current.id(),
            null,
            InputBroker::isLive,
            c -> {
              final InputRequest moved =
                  replace(
                      c,
                      InputState.ESCALATED,
                      next.name(),
                      List.of(),
                      advice == null ? c.advice() : advice,
                      next.timeout() == null ? null : clock.instant().plus(next.timeout()),
                      List.of());
              return retarget(moved, TierTargets.selectors(moved, next, directory(), references));
            })
        .flatMap(
            change -> {
              if (!change.applied()) {
                return Mono.just(change.request());
              }
              escalated(change.request(), reason, by);
              return skipUnreachable(change.request(), route);
            });
  }

  /** The scopes a {@code supervisor:above} tier must strictly cover. */
  private List<SupervisorScope> references(
      final InputRequest current, final @Nullable Responder by) {
    final EligibleSupervisors bound = directory();
    if (by != null && by.type() == Responder.Type.SUPERVISOR) {
      return bound.bound().stream()
          .filter(c -> c.id().equals(by.id()))
          .map(EligibleSupervisors.Candidate::scope)
          .toList();
    }
    return TierTargets.supervisors(current, current.targets(), bound).stream()
        .map(EligibleSupervisors.Candidate::scope)
        .toList();
  }

  /**
   * Hands a request on at once when its tier cannot be answered: no supervisor to ask, or fewer
   * than a quorum needs.
   */
  private Mono<InputRequest> skipUnreachable(final InputRequest request, final InputRoute route) {
    final Tier tier = tierOf(route, request.tier());
    if (tier == null) {
      return Mono.just(request);
    }
    final int reach = TierTargets.supervisors(request, request.targets(), directory()).size();
    final String why;
    if (!TierTargets.namesOthers(request.targets()) && reach == 0) {
      why = "no-target";
    } else if (tier.mode() == Mode.QUORUM && reach < tier.quorum()) {
      why = "quorum-unreachable";
    } else {
      return Mono.just(request);
    }
    return moveUp(request, route, why, null, null).defaultIfEmpty(request);
  }

  private static int indexOf(final InputRoute route, final String tierName) {
    for (int i = 0; i < route.tiers().size(); i++) {
      if (route.tiers().get(i).name().equals(tierName)) {
        return i;
      }
    }
    return -1;
  }

  private static @Nullable Tier tierOf(final InputRoute route, final String tierName) {
    final int at = indexOf(route, tierName);
    return at < 0 ? null : route.tiers().get(at);
  }

  /**
   * Cancels a request.
   *
   * @param requestId the request
   * @param reason why, for example {@code node-cancelled} or {@code run-ended}
   * @return the request: cancelled if this call won, otherwise as it is now; empty if unknown
   */
  public Mono<InputRequest> cancel(final String requestId, final String reason) {
    return transition(
            requestId,
            null,
            InputBroker::isLive,
            current -> advance(current, InputState.CANCELLED, current.advice()))
        .flatMap(
            change -> {
              if (!change.applied()) {
                return Mono.just(change.request());
              }
              return cancelled(change.request(), reason).thenReturn(change.request());
            });
  }

  /**
   * Cancels every open request of a run, for when the run ends.
   *
   * @param executionId the run
   * @param reason why, for example {@code run-ended}
   * @return completes when they are cancelled
   */
  public Mono<Void> cancelExecution(final String executionId, final String reason) {
    return store
        .listOpen(null, executionId)
        .concatMap(request -> cancel(request.id(), reason))
        .then();
  }

  /**
   * Ends the requests past their deadline. The sweeper calls it on a timer.
   *
   * @return completes when the due requests are handled
   */
  public Mono<Void> sweep() {
    return Flux.defer(() -> store.dueBefore(clock.instant()))
        .concatMap(
            due ->
                expire(due)
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .setCause(error)
                              .addKeyValue(LOG_REQUEST_ID, due.id())
                              .log("Could not end an input request past its deadline");
                          return Mono.empty();
                        }))
        .then();
  }

  /**
   * Reads a request.
   *
   * @param requestId the request id
   * @return the request, or empty
   */
  public Mono<InputRequest> find(final String requestId) {
    return store.findById(requestId);
  }

  /**
   * Lists the requests that are not terminal.
   *
   * @param sessionId only this session, or {@code null}
   * @param executionId only this run, or {@code null}
   * @return the open requests, oldest first
   */
  public Flux<InputRequest> listOpen(
      final @Nullable String sessionId, final @Nullable String executionId) {
    return store.listOpen(sessionId, executionId);
  }

  private Mono<InputRequest> openRequest(final InputIdentity who, final InputRequestSpec spec) {
    return store
        .listOpen(who.sessionId(), null)
        .collectList()
        .flatMap(
            inSession -> {
              final InputRequest existing =
                  inSession.stream()
                      .filter(request -> request.key().equals(spec.key()))
                      .findFirst()
                      .orElse(null);
              if (existing != null) {
                return Mono.just(existing);
              }
              final String denial = denial(who, spec, inSession);
              if (denial != null) {
                return deny(spec, denial);
              }
              return routes
                  .resolve(spec, who.sessionId())
                  .flatMap(
                      route -> {
                        final InputRequest created = create(who, spec, route);
                        return store
                            .createIfAbsent(created)
                            .doOnNext(
                                stored -> {
                                  if (stored.id().equals(created.id())) {
                                    asked(stored);
                                  }
                                })
                            .flatMap(
                                stored ->
                                    stored.id().equals(created.id())
                                        ? skipUnreachable(stored, route)
                                        : Mono.just(stored));
                      });
            });
  }

  /** The rule an ask breaks, or {@code null}. */
  private @Nullable String denial(
      final InputIdentity who, final InputRequestSpec spec, final List<InputRequest> inSession) {
    if (spec.ttl().compareTo(limits.maxTtl()) > 0) {
      return "ttl";
    }
    final long inExecution =
        inSession.stream().filter(r -> r.executionId().equals(who.executionId())).count();
    if (inExecution >= limits.maxOpenPerExecution()) {
      return "open-per-execution";
    }
    return inSession.size() >= limits.maxOpenPerSession() ? "open-per-session" : null;
  }

  private Mono<InputRequest> deny(final InputRequestSpec spec, final String rule) {
    meters.counter(DENIED_METRIC, "rule", rule).increment();
    final String message =
        switch (rule) {
          case "ttl" -> "ttl " + spec.ttl() + " is longer than the limit " + limits.maxTtl();
          case "open-per-execution" ->
              "The run already has " + limits.maxOpenPerExecution() + " open input requests";
          default ->
              "The session already has " + limits.maxOpenPerSession() + " open input requests";
        };
    return Mono.error(new InputLimitException(spec.key(), message));
  }

  private InputRequest create(
      final InputIdentity who, final InputRequestSpec spec, final InputRoute route) {
    final Instant now = clock.instant();
    // ponytail: the first tier; a run raised by a supervisor (phase 8) will start higher up
    final Tier first = route.tiers().getFirst();
    final InputRequest draft =
        new InputRequest(
            UUID.randomUUID().toString(),
            spec.key(),
            who.sessionId(),
            who.workflowId(),
            who.executionId(),
            who.nodeId(),
            spec,
            InputState.OPEN,
            0,
            first.name(),
            first.responders(),
            null,
            now.plus(spec.ttl()),
            first.timeout() == null ? null : now.plus(first.timeout()),
            List.of(),
            now,
            now);
    return retarget(draft, TierTargets.selectors(draft, first, directory(), List.of()));
  }

  /** The same version of a request, with other selectors. */
  private static InputRequest retarget(final InputRequest request, final List<String> targets) {
    return new InputRequest(
        request.id(),
        request.key(),
        request.sessionId(),
        request.workflowId(),
        request.executionId(),
        request.nodeId(),
        request.spec(),
        request.state(),
        request.version(),
        request.tier(),
        targets,
        request.advice(),
        request.deadline(),
        request.tierDeadline(),
        request.votes(),
        request.createdAt(),
        request.updatedAt());
  }

  private EligibleSupervisors directory() {
    final EligibleSupervisors found = supervisors == null ? null : supervisors.getIfAvailable();
    return found == null ? EligibleSupervisors.NONE : found;
  }

  /** Waits for the end of a request; the last caller to cancel cancels the request. */
  private Mono<InputResponse> await(final InputRequest request) {
    final Waiting waiter = waiting.computeIfAbsent(request.id(), _ -> new Waiting());
    return waiter
        .sink
        .asMono()
        .doOnSubscribe(
            _ -> {
              waiter.callers.incrementAndGet();
              // the request may have ended before this caller got here
              store
                  .findById(request.id())
                  .filter(found -> found.state().isTerminal())
                  .flatMap(this::settle)
                  .subscribe();
            })
        .doFinally(
            signal -> {
              if (waiter.callers.decrementAndGet() == 0 && signal == SignalType.CANCEL) {
                cancel(request.id(), "node-cancelled").subscribe();
              }
            });
  }

  private record Change(InputRequest request, boolean applied) {}

  /**
   * Moves a request to its next state with a compare-and-set. A request in a state the move does
   * not start from, or at another version than expected, is returned as it is.
   */
  private Mono<Change> transition(
      final String requestId,
      final @Nullable Long expectedVersion,
      final Predicate<InputState> from,
      final UnaryOperator<InputRequest> change) {
    return store
        .findById(requestId)
        .flatMap(
            current -> {
              if (!from.test(current.state())
                  || expectedVersion != null && current.version() != expectedVersion) {
                return Mono.just(new Change(current, false));
              }
              final InputRequest next = change.apply(current);
              return store
                  .compareAndSet(requestId, current.version(), next)
                  .flatMap(
                      swapped ->
                          swapped
                              ? Mono.just(new Change(next, true))
                              // the version moved: read again and decide on what is there now
                              : transition(requestId, expectedVersion, from, change));
            });
  }

  private static boolean isLive(final InputState state) {
    return !state.isTerminal();
  }

  private InputRequest advance(
      final InputRequest current, final InputState state, final @Nullable String advice) {
    return replace(
        current,
        state,
        current.tier(),
        current.targets(),
        advice,
        current.tierDeadline(),
        current.votes());
  }

  /** The next version of a request with the given state, tier and votes. */
  private InputRequest replace(
      final InputRequest current,
      final InputState state,
      final String tier,
      final List<String> targets,
      final @Nullable String advice,
      final @Nullable Instant tierDeadline,
      final List<Vote> votes) {
    return new InputRequest(
        current.id(),
        current.key(),
        current.sessionId(),
        current.workflowId(),
        current.executionId(),
        current.nodeId(),
        current.spec(),
        state,
        current.version() + 1,
        tier,
        targets,
        advice,
        current.deadline(),
        tierDeadline,
        votes,
        current.createdAt(),
        clock.instant());
  }

  private Mono<Void> expire(final InputRequest due) {
    final Instant now = clock.instant();
    final Instant limit = due.deadline();
    final boolean overall = limit != null && !limit.isAfter(now);
    return routes
        .resolve(due.spec(), due.sessionId())
        .flatMap(
            route -> {
              if (!overall) {
                // only the tier ran out of time: hand the request up, or end it at the last tier
                return moveUp(due, route, "tier-timeout", null, null)
                    .switchIfEmpty(Mono.defer(() -> finalTimeout(due, route).then(Mono.empty())))
                    .then();
              }
              return due.spec().onTimeout() == TimeoutAction.ESCALATE
                  ? moveUp(due, route, "deadline", null, null)
                      .switchIfEmpty(Mono.defer(() -> end(due).then(Mono.empty())))
                      .then()
                  : end(due);
            });
  }

  /** The last tier gave up: the route's final action, then the request's own. */
  private Mono<Void> finalTimeout(final InputRequest due, final InputRoute route) {
    final FinalAction action = route.onFinalTimeout();
    if (action == FinalAction.FAIL) {
      return expireNow(due);
    }
    if (action == FinalAction.USE_DEFAULT) {
      return due.spec().defaultDecision() == null ? expireNow(due) : useDefault(due);
    }
    return end(due);
  }

  /** The request's own {@code onTimeout}. */
  private Mono<Void> end(final InputRequest due) {
    return due.spec().onTimeout() == TimeoutAction.USE_DEFAULT ? useDefault(due) : expireNow(due);
  }

  private Mono<Void> useDefault(final InputRequest due) {
    // the spec refuses USE_DEFAULT without a default decision
    final InputResponse response =
        new InputResponse(
            due.id(),
            Objects.requireNonNull(due.spec().defaultDecision()),
            null,
            Map.of(),
            Responder.SYSTEM,
            "deadline reached",
            null,
            clock.instant());
    return finalAnswer(due.id(), null, response).then();
  }

  private Mono<Void> expireNow(final InputRequest due) {
    return transition(
            due.id(),
            null,
            InputBroker::isLive,
            current -> advance(current, InputState.EXPIRED, current.advice()))
        .filter(Change::applied)
        .flatMap(change -> expired(change.request()));
  }

  // --- what follows a change: counters, log, event, and the waiting caller ---

  private void asked(final InputRequest request) {
    open.incrementAndGet();
    count(request, "asked");
    record(
        request,
        "INPUT_REQUESTED",
        Map.of(
            "kind",
            request.spec().kind().name(),
            PAYLOAD_RISK,
            request.spec().risk().name(),
            "requester",
            request.spec().requester()));
    feed.publish(new ExecutionEvent.InputRequested(request, clock.instant()));
    log.atInfo()
        .addKeyValue(LOG_REQUEST_ID, request.id())
        .addKeyValue(LOG_EXECUTION_ID, request.executionId())
        .addKeyValue(LOG_NODE_ID, request.nodeId())
        .log("Input requested");
  }

  private void escalated(
      final InputRequest request, final String reason, final @Nullable Responder by) {
    count(request, "escalated");
    meters.counter("yukta.input.escalations", "tier", request.tier()).increment();
    final Map<String, Object> facts = new ConcurrentHashMap<>();
    facts.put(PAYLOAD_REASON, truncated(reason));
    facts.put(PAYLOAD_RISK, request.spec().risk().name());
    if (by != null) {
      facts.put(PAYLOAD_RESPONDER_TYPE, by.type().name());
      facts.put(PAYLOAD_RESPONDER_ID, by.id());
    }
    record(request, "INPUT_ESCALATED", facts);
    feed.publish(new ExecutionEvent.InputEscalated(request, reason, clock.instant()));
    logEnd(request, "Input escalated");
  }

  private InputRequest answered(final InputRequest request, final InputResponse response) {
    final Responder responder = response.responder();
    finish(request, responder.type() == Responder.Type.SYSTEM ? "defaulted" : "answered");
    final Map<String, Object> facts = new ConcurrentHashMap<>();
    facts.put(PAYLOAD_RESPONDER_TYPE, responder.type().name());
    facts.put(PAYLOAD_RESPONDER_ID, responder.id());
    facts.put("decision", response.decision());
    facts.put(PAYLOAD_RISK, request.spec().risk().name());
    if (response.rationale() != null) {
      facts.put("rationale", truncated(redactor.text(response.rationale())));
    }
    record(request, "INPUT_ANSWERED", facts);
    feed.publish(
        new ExecutionEvent.InputAnswered(
            request.executionId(),
            request.sessionId(),
            request.workflowId(),
            request.id(),
            responder,
            response.decision(),
            clock.instant(),
            request.nodeId()));
    logEnd(request, "Input answered");
    settle(request).subscribe();
    return request;
  }

  private Mono<Void> expired(final InputRequest request) {
    finish(request, "expired");
    record(request, "INPUT_EXPIRED", Map.of());
    feed.publish(
        new ExecutionEvent.InputExpired(
            request.executionId(),
            request.sessionId(),
            request.workflowId(),
            request.id(),
            clock.instant(),
            request.nodeId()));
    logEnd(request, "Input expired");
    return settle(request);
  }

  private Mono<Void> cancelled(final InputRequest request, final String reason) {
    finish(request, "cancelled");
    record(request, "INPUT_CANCELLED", Map.of(PAYLOAD_REASON, reason));
    feed.publish(
        new ExecutionEvent.InputCancelled(
            request.executionId(),
            request.sessionId(),
            request.workflowId(),
            request.id(),
            reason,
            clock.instant(),
            request.nodeId()));
    logEnd(request, "Input cancelled");
    cancelReasons.put(request.id(), reason);
    return settle(request);
  }

  /**
   * Tells the callers waiting for a request how it ended. Only finished requests get here:
   * answered, expired, or else cancelled.
   */
  private Mono<Void> settle(final InputRequest request) {
    return switch (request.state()) {
      case ANSWERED ->
          store
              .findResponse(request.id())
              .doOnNext(response -> emit(request.id(), sink -> sink.tryEmitValue(response)))
              .then();
      case EXPIRED ->
          Mono.fromRunnable(
              () ->
                  emit(
                      request.id(),
                      sink ->
                          sink.tryEmitError(
                              new InputExpiredException(
                                  request.id(), "Nobody answered before the deadline"))));
      default ->
          Mono.fromRunnable(
              () ->
                  emit(
                      request.id(),
                      sink ->
                          sink.tryEmitError(
                              new InputCancelledException(
                                  request.id(),
                                  "The request was cancelled: "
                                      + cancelReasons.getOrDefault(request.id(), "cancelled")))));
    };
  }

  private void emit(final String requestId, final Consumer<Sinks.One<InputResponse>> action) {
    final Waiting waiter = waiting.remove(requestId);
    if (waiter != null) {
      action.accept(waiter.sink);
    }
    cancelReasons.remove(requestId);
  }

  private void finish(final InputRequest request, final String outcome) {
    open.decrementAndGet();
    count(request, outcome);
    meters
        .timer(WAIT_METRIC, "tier", request.tier())
        .record(Duration.between(request.createdAt(), request.updatedAt()));
  }

  private void count(final InputRequest request, final String outcome) {
    meters
        .counter(
            REQUESTS_METRIC,
            "kind",
            request.spec().kind().name(),
            "risk",
            request.spec().risk().name(),
            "outcome",
            outcome)
        .increment();
  }

  /** Records what happened under the run; never the prompt, the context or an answer text. */
  private void record(
      final InputRequest request, final String event, final Map<String, Object> facts) {
    final SequencedMap<String, Object> payload = new LinkedHashMap<>();
    payload.put(ExecutionLogEvent.INPUT_EVENT_KEY, event);
    payload.put(PAYLOAD_REQUEST_ID, request.id());
    payload.put(PAYLOAD_TIER, request.tier());
    payload.putAll(facts);
    executionLog.append(
        ExecutionLogEvent.input(
            request.executionId(),
            request.sessionId(),
            request.workflowId(),
            request.nodeId(),
            payload));
  }

  private void logEnd(final InputRequest request, final String message) {
    log.atInfo()
        .addKeyValue(LOG_REQUEST_ID, request.id())
        .addKeyValue(LOG_EXECUTION_ID, request.executionId())
        .addKeyValue(LOG_NODE_ID, request.nodeId())
        .addKeyValue("state", request.state())
        .log(message);
  }

  /** A text for the log, cut to what the log keeps. */
  private static String truncated(final String text) {
    return text.length() > LOG_TEXT_MAX ? text.substring(0, LOG_TEXT_MAX) : text;
  }

  /** The callers waiting for one request. */
  private static final class Waiting {

    /** Completes with the answer or the failure. */
    private final Sinks.One<InputResponse> sink = Sinks.one();

    /** How many callers are subscribed. */
    private final AtomicInteger callers = new AtomicInteger();
  }
}
