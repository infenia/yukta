// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs other supervisors as one. The children are not bound separately, so nothing is subscribed
 * twice and the composite has one id and one scope (the binding's).
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "the children are built for this composite and not shared")
public final class CompositeSupervisor implements ExecutionSupervisor {

  /** How long one child may take to review. */
  private static final Duration REVIEW_TIMEOUT = Duration.ofSeconds(4);

  /** The id until a binding replaces it. */
  private final String id;

  /** How the children are combined. */
  private final CompositeSettings settings;

  /** The profile ids of the children, in the order of {@link #children}. */
  private final List<String> childIds;

  /** The children, built from their profiles. */
  private final List<ExecutionSupervisor> children;

  /** Time for the quorum window. */
  private final Clock clock;

  /**
   * Creates the composite.
   *
   * @param id the supervisor id
   * @param settings the mode and the review rule
   * @param childIds the profile ids of the children
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
   * The profile ids of the children.
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
  public int order() {
    return children.stream().mapToInt(ExecutionSupervisor::order).min().orElse(0);
  }

  @Override
  public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return switch (settings.mode()) {
      case CHAIN, FIRST_MATCH ->
          events.concatMap(this::decide).concatMapIterable(d -> d.commands());
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
    final Map<ExecutionControlCommand, Votes> open = new HashMap<>();
    final List<Flux<Vote>> votes = new ArrayList<>();
    for (int index = 0; index < children.size(); index++) {
      final int voter = index;
      votes.add(children.get(index).supervise(shared).map(command -> new Vote(voter, command)));
    }
    return Flux.merge(votes)
        .<ExecutionControlCommand>handle(
            (vote, sink) -> {
              final Instant now = clock.instant();
              synchronized (open) {
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
              .onErrorReturn(Decision.deny("review failed"))
              .map(decision -> new Verdict(childId, decision)));
    }
    return switch (settings.review()) {
      case ALL ->
          Flux.concat(reviews)
              .filter(verdict -> !verdict.decision().allowed())
              .next()
              .map(Verdict::denial)
              .defaultIfEmpty(Decision.ALLOW);
      case ANY -> Flux.merge(reviews).collectList().map(list -> any(list));
      case MAJORITY -> Flux.merge(reviews).collectList().map(list -> majority(list));
    };
  }

  private static Decision any(final List<Verdict> verdicts) {
    return verdicts.isEmpty() || verdicts.stream().anyMatch(v -> v.decision().allowed())
        ? Decision.ALLOW
        : verdicts.get(0).denial();
  }

  private static Decision majority(final List<Verdict> verdicts) {
    final long allowed = verdicts.stream().filter(v -> v.decision().allowed()).count();
    return verdicts.isEmpty() || allowed * 2 > verdicts.size()
        ? Decision.ALLOW
        : Decision.deny(
            "only " + allowed + " of " + verdicts.size() + " supervisors allowed the command");
  }

  /** One child's review. */
  private record Verdict(String child, Decision decision) {
    Decision denial() {
      return Decision.deny(child + ": " + decision.reason());
    }
  }

  /** A command sent by the child with this index. */
  private record Vote(int voter, ExecutionControlCommand command) {}

  /** The children that sent one command since it was first seen. */
  private record Votes(Instant opened, Set<Integer> voters) {
    Votes(final Instant opened) {
      this(opened, new HashSet<>());
    }
  }
}
