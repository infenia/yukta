// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs other supervisors as one. The children are not bound separately, so nothing is subscribed
 * twice and the composite has one id and one scope (the attachment's).
 */
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods"
})
public final class CompositeSupervisor implements ExecutionSupervisor {

  /** How long one child may take to review. */
  private static final Duration REVIEW_TIMEOUT = Duration.ofSeconds(4);

  /** The id until a attachment replaces it. */
  private final String id;

  /** How the children are combined. */
  private final CompositeSettings settings;

  /** The supervisor ids of the children, in the order of {@link #children}. */
  private final List<String> childIds;

  /** The children, built from their supervisors. */
  private final List<ExecutionSupervisor> children;

  /** Time for the quorum window. */
  private final Clock clock;

  /**
   * Creates the composite.
   *
   * @param id the supervisor id
   * @param settings the mode and the review rule
   * @param childIds the supervisor ids of the children
   * @param children the children, same order
   * @param clock time for the quorum window
   */
  public CompositeSupervisor(
      final String id,
      final CompositeSettings settings,
      final List<String> childIds,
      final List<ExecutionSupervisor> children,
      final Clock clock) {
    this.id = id;
    this.settings = settings;
    this.childIds = List.copyOf(childIds);
    this.children = List.copyOf(children);
    this.clock = clock;
  }

  /**
   * The supervisor ids of the children.
   *
   * @return the ids, in order
   */
  public List<String> childIds() {
    return childIds;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return id;
  }

  @Override
  public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return switch (settings.mode()) {
      case CHAIN, FIRST_MATCH ->
          events.concatMap(this::decide).concatMapIterable(ControlDecision::commands);
      case PARALLEL ->
          Flux.defer(
              () -> {
                final Flux<ExecutionEvent> shared = events.publish().autoConnect(children.size());
                return Flux.merge(children.stream().map(child -> child.supervise(shared)).toList());
              });
      case QUORUM -> Flux.defer(() -> quorum(events));
    };
  }

  /** The decision of the first child that has one, for CHAIN and FIRST_MATCH. */
  private Mono<ControlDecision> decide(final ExecutionEvent event) {
    final boolean skipEmpty = settings.mode() == CompositeMode.CHAIN;
    return Flux.fromIterable(children)
        .map(ControlPlanePlugin.class::cast)
        .concatMap(
            plane ->
                plane
                    .decide(event)
                    .timeout(plane.decisionTimeout(), Mono.empty())
                    .onErrorResume(_ -> Mono.empty()))
        .filter(decision -> !skipEmpty || !decision.commands().isEmpty())
        .next();
  }

  /** Sends a command once enough children have sent an equal one within the window. */
  private Flux<ExecutionControlCommand> quorum(final Flux<ExecutionEvent> events) {
    final Flux<ExecutionEvent> shared = events.publish().autoConnect(children.size());
    final Map<ExecutionControlCommand, Votes> open = new ConcurrentHashMap<>();
    final ReentrantLock lock = new ReentrantLock();
    final List<Flux<Vote>> votes = new ArrayList<>();
    for (int index = 0; index < children.size(); index++) {
      final int voter = index;
      votes.add(children.get(index).supervise(shared).map(command -> new Vote(voter, command)));
    }
    return Flux.merge(votes)
        .<ExecutionControlCommand>handle(
            (vote, sink) -> {
              final Instant now = clock.instant();
              lock.lock();
              try {
                final Iterator<Votes> expired = open.values().iterator();
                while (expired.hasNext()) {
                  if (expired.next().opened().plus(settings.window()).isBefore(now)) {
                    expired.remove();
                  }
                }
                final Votes seen = open.computeIfAbsent(vote.command(), _ -> new Votes(now));
                // emits once, at the vote that reaches the quorum
                if (seen.voters().add(vote.voter()) && seen.voters().size() == settings.quorum()) {
                  sink.next(vote.command());
                }
              } finally {
                lock.unlock();
              }
            });
  }

  @Override
  public Mono<Decision> review(final ControlRequest request) {
    final boolean stop =
        request.command() instanceof ExecutionControlCommand.StopWorkflowCommand
            || request.command() instanceof ExecutionControlCommand.StopNodeCommand;
    final List<Mono<Verdict>> reviews = new ArrayList<>();
    for (int index = 0; index < children.size(); index++) {
      final String childId = childIds.get(index);
      final ExecutionSupervisor child = children.get(index);
      reviews.add(
          Mono.defer(() -> child.review(request))
              .timeout(
                  REVIEW_TIMEOUT,
                  Mono.just(stop ? Decision.ALLOW : Decision.deny("review timed out")))
              .switchIfEmpty(
                  Mono.just(stop ? Decision.ALLOW : Decision.deny("review returned nothing")))
              .onErrorReturn(stop ? Decision.ALLOW : Decision.deny("review failed"))
              .map(decision -> new Verdict(childId, decision)));
    }
    // all children are asked at once, so the slowest one, not their sum, has to fit the outer
    // review timeout
    final Mono<List<Verdict>> all = Flux.mergeSequential(reviews).collectList();
    return switch (settings.review()) {
      case ALL -> all.map(CompositeSupervisor::all);
      case ANY -> all.map(CompositeSupervisor::any);
      case MAJORITY -> all.map(CompositeSupervisor::majority);
    };
  }

  /** The first refusal; else the approvals as one need; else allow. */
  private static Decision all(final List<Verdict> verdicts) {
    return verdicts.stream()
        .filter(v -> !v.decision().allowed() && !v.decision().requiresApproval())
        .findFirst()
        .map(Verdict::denial)
        .orElseGet(() -> approvals(verdicts).orElse(Decision.ALLOW));
  }

  /** Allow if one allows; else the approvals as one need, if any; else the first refusal. */
  private static Decision any(final List<Verdict> verdicts) {
    if (verdicts.stream().anyMatch(v -> v.decision().allowed())) {
      return Decision.ALLOW;
    }
    return approvals(verdicts).orElseGet(() -> verdicts.get(0).denial());
  }

  /** Allow on a majority; approval can still make one, so it is asked for before refusing. */
  private static Decision majority(final List<Verdict> verdicts) {
    final long allowed = verdicts.stream().filter(v -> v.decision().allowed()).count();
    if (allowed * 2 > verdicts.size()) {
      return Decision.ALLOW;
    }
    final long pending = verdicts.stream().filter(v -> v.decision().requiresApproval()).count();
    return (allowed + pending) * 2 > verdicts.size()
        ? approvals(verdicts).orElseThrow()
        : Decision.deny(
            "only " + allowed + " of " + verdicts.size() + " supervisors allowed the command");
  }

  /** The children's needs for approval as one: highest risk, first route, shortest ttl. */
  private static Optional<Decision> approvals(final List<Verdict> verdicts) {
    final List<Verdict> asking =
        verdicts.stream().filter(v -> v.decision().requiresApproval()).toList();
    if (asking.isEmpty()) {
      return Optional.empty();
    }
    InputRisk risk = InputRisk.LOW;
    String route = null;
    Duration ttl = null;
    for (final Verdict verdict : asking) {
      final ApprovalNeed need = Objects.requireNonNull(verdict.decision().approval());
      risk = need.risk().atLeast(risk) ? need.risk() : risk;
      route = route == null ? need.routeName() : route;
      ttl = ttl == null || need.ttl() != null && need.ttl().compareTo(ttl) < 0 ? need.ttl() : ttl;
    }
    return Optional.of(
        Decision.requireApproval(
            asking.stream().map(Verdict::reason).collect(Collectors.joining("; ")),
            new ApprovalNeed(risk, route, ttl)));
  }

  /** One child's review. */
  private record Verdict(String child, Decision decision) {
    /* default */ Decision denial() {
      return Decision.deny(reason());
    }

    /* default */ String reason() {
      return child + ": " + Objects.requireNonNullElse(decision.reason(), "approval required");
    }
  }

  /** A command sent by the child with this index. */
  private record Vote(int voter, ExecutionControlCommand command) {}

  /** The children that sent one command since it was first seen. */
  private record Votes(Instant opened, Set<Integer> voters) {
    /* default */ Votes(final Instant opened) {
      this(opened, new HashSet<>());
    }
  }
}
