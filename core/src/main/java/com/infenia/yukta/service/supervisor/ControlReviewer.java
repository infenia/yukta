// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.input.InputRisk;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Asks every {@link ExecutionSupervisor} whether a control command may run. */
@Slf4j
@Component
public class ControlReviewer {

  /** The registered supervisors; looked up per review so late registrations count. */
  private final ObjectProvider<ExecutionSupervisor> supervisors;

  /** The supervisors attached at run time; set by Spring, empty without it. */
  private AttachedSupervisors attached = new AttachedSupervisors();

  /** Finds the host, to record what a dry-run supervisor would have refused. */
  private @Nullable ObjectProvider<SupervisorHost> hostLookup;

  /** Whether every supervisor only records ({@code yukta.supervisor.dry-run}). */
  private boolean dryRun;

  /** Longest a single supervisor may take to answer; a hung review must not block a run. */
  private final Duration timeout;

  /**
   * Create the reviewer.
   *
   * @param supervisors the registered supervisors
   * @param properties the settings; {@code yukta.supervisor.review-timeout} is the longest a
   *     supervisor may take to answer
   */
  @Autowired
  public ControlReviewer(
      final ObjectProvider<ExecutionSupervisor> supervisors, final YuktaProperties properties) {
    this(supervisors, properties.supervisor().reviewTimeout());
  }

  /**
   * Create the reviewer.
   *
   * @param supervisors the registered supervisors
   * @param timeout longest a supervisor may take to answer
   */
  public ControlReviewer(
      final ObjectProvider<ExecutionSupervisor> supervisors, final Duration timeout) {
    this.supervisors = supervisors;
    this.timeout = timeout;
  }

  /**
   * Create the reviewer with the default 5 s time limit.
   *
   * @param supervisors the registered supervisors
   */
  public ControlReviewer(final ObjectProvider<ExecutionSupervisor> supervisors) {
    this(supervisors, Duration.ofSeconds(5));
  }

  /**
   * Includes the supervisors attached at run time in reviews.
   *
   * @param attachedSupervisors the set
   */
  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects the shared service, which is meant to be held")
  public void setAttachedSupervisors(final AttachedSupervisors attachedSupervisors) {
    this.attached = attachedSupervisors;
  }

  /**
   * Lets a dry-run supervisor's refusals be shown in the cockpit; looked up when needed, because
   * the host depends on the dispatcher.
   *
   * @param host the host lookup
   * @param properties the settings; {@code yukta.supervisor.dry-run} is the global flag
   */
  @Autowired
  public void setDryRun(
      final ObjectProvider<SupervisorHost> host, final YuktaProperties properties) {
    this.hostLookup = host;
    this.dryRun = properties.supervisor().dryRun();
  }

  /**
   * Same as {@link #setDryRun(ObjectProvider, YuktaProperties)} with an explicit flag.
   *
   * @param host the host lookup
   * @param dryRun the global dry-run flag
   */
  public void setDryRun(final ObjectProvider<SupervisorHost> host, final boolean dryRun) {
    this.hostLookup = host;
    this.dryRun = dryRun;
  }

  /**
   * Reviews a command in supervisor order, stopping at the first refusal. A need for approval
   * counts as a refusal here: use {@link #verdict} where a command can be parked.
   *
   * @param request the command and who sent it
   * @return completes if every supervisor allows it; fails with {@link ControlRejectedException}
   *     otherwise. A supervisor that fails to answer counts as a refusal, and so does one that
   *     takes longer than {@code yukta.supervisor.review-timeout} (default 5 s), except for a stop:
   *     a stop is allowed when the review times out, so a hung supervisor cannot keep a person from
   *     stopping a run.
   */
  public Mono<Void> review(final ControlRequest request) {
    return verdict(request, Set.of())
        .flatMap(
            verdict ->
                switch (verdict) {
                  case ReviewVerdict.Allowed _ -> Mono.<Void>empty();
                  case ReviewVerdict.Denied denied ->
                      Mono.<Void>error(new ControlRejectedException(denied.reason()));
                  case ReviewVerdict.NeedsApproval needs ->
                      Mono.<Void>error(
                          new ControlRejectedException(
                              needs.reason()
                                  + " (needs approval, which this command cannot wait for)"));
                });
  }

  /**
   * What the supervisors together say about a command. They are asked in order; the first refusal
   * ends the review and wins over any need for approval. Several needs are merged into one: the
   * highest risk, the first route named, the shortest ttl, every reason and every supervisor that
   * asked. A supervisor in {@code satisfied} has already had its approval for this command, so its
   * need for approval counts as an allow; its refusal still counts.
   *
   * <p>A stop sent by a person (any sender that is not a supervisor, by {@link
   * ControlActor#KIND_KEY}) is never held for approval: it is allowed with a warning, as when a
   * review times out, so a person can always stop a run.
   *
   * @param request the command and who sent it
   * @param satisfied the supervisors whose approval request for this command was approved
   * @return the verdict; a supervisor that fails or times out answers as in {@link #review}
   */
  public Mono<ReviewVerdict> verdict(final ControlRequest request, final Set<String> satisfied) {
    return Mono.deferContextual(
        context -> {
          final boolean byPerson =
              context.getOrDefault(ControlActor.KIND_KEY, null) != ControlActor.Kind.SUPERVISOR;
          return Flux.fromStream(
                  () ->
                      Stream.concat(supervisors.stream(), attached.stream())
                          .filter(
                              supervisor ->
                                  supervisor
                                      .scope()
                                      .matches(
                                          request.sessionId(),
                                          request.workflowId(),
                                          request.nodeId()))
                          .sorted(Comparator.comparingInt(ExecutionSupervisor::order)))
              .concatMap(
                  supervisor ->
                      decide(supervisor, request)
                          .map(decision -> new Vote(supervisor.id(), decision)))
              .filter(vote -> !vote.decision().allowed() && !vote.satisfiedBy(satisfied))
              .takeUntil(vote -> !vote.decision().requiresApproval())
              .collectList()
              .map(votes -> combine(request, votes, byPerson));
        });
  }

  /** One supervisor's answer, within the time limit; an error is a refusal. */
  private Mono<Decision> decide(
      final ExecutionSupervisor supervisor, final ControlRequest request) {
    return Mono.defer(() -> supervisor.review(request))
        .timeout(
            timeout, Mono.fromSupplier(() -> fallback(supervisor, request, "review timed out")))
        .onErrorResume(
            error -> {
              log.atWarn()
                  .setCause(error)
                  .addKeyValue("supervisor", supervisor.id())
                  .log("Supervisor review failed");
              return Mono.just(Decision.deny("review failed"));
            })
        .map(decision -> dryRunned(supervisor, request, decision));
  }

  /** The answers that are not allows, in order; at most one refusal, as the last. */
  private static ReviewVerdict combine(
      final ControlRequest request, final List<Vote> votes, final boolean byPerson) {
    if (votes.isEmpty()) {
      return ReviewVerdict.ALLOWED;
    }
    final Vote last = votes.getLast();
    if (!last.decision().requiresApproval()) {
      return new ReviewVerdict.Denied(last.supervisorId() + ": " + last.decision().reason());
    }
    if (byPerson && isStop(request)) {
      log.atWarn()
          .addKeyValue("command", request.command().getClass().getSimpleName())
          .addKeyValue("actor", request.actor())
          .log("A supervisor wants a stop approved; a person sent it, so it runs now");
      return ReviewVerdict.ALLOWED;
    }
    return merged(votes);
  }

  /** Several needs for approval as one request. */
  private static ReviewVerdict merged(final List<Vote> votes) {
    InputRisk risk = InputRisk.LOW;
    String route = null;
    Duration ttl = null;
    final List<String> reasons = new ArrayList<>();
    final List<String> askedBy = new ArrayList<>();
    for (final Vote vote : votes) {
      final ApprovalNeed need = Objects.requireNonNull(vote.decision().approval());
      risk = need.risk().atLeast(risk) ? need.risk() : risk;
      route = route == null ? need.routeName() : route;
      ttl = shorter(ttl, need.ttl());
      reasons.add(
          vote.supervisorId()
              + ": "
              + Objects.requireNonNullElse(vote.decision().reason(), "approval required"));
      askedBy.add(vote.supervisorId());
    }
    return new ReviewVerdict.NeedsApproval(
        new ApprovalNeed(risk, route, ttl), String.join("; ", reasons), askedBy);
  }

  private static @Nullable Duration shorter(
      final @Nullable Duration current, final @Nullable Duration next) {
    return current == null || next != null && next.compareTo(current) < 0 ? next : current;
  }

  private static boolean isStop(final ControlRequest request) {
    return request.command() instanceof ExecutionControlCommand.StopWorkflowCommand
        || request.command() instanceof ExecutionControlCommand.StopNodeCommand;
  }

  /**
   * One supervisor's decision, by its id.
   *
   * @param supervisorId the supervisor
   * @param decision what it said
   */
  private record Vote(String supervisorId, Decision decision) {

    /** A need for approval that this supervisor's approved request already met. */
    private boolean satisfiedBy(final Set<String> satisfied) {
      return decision.requiresApproval() && satisfied.contains(supervisorId);
    }
  }

  /** A refusal or a need for approval from a dry-run supervisor is recorded, not enforced. */
  private Decision dryRunned(
      final ExecutionSupervisor supervisor, final ControlRequest request, final Decision decision) {
    final boolean dry = dryRun || supervisor instanceof BoundSupervisor bound && bound.dryRun();
    if (decision.allowed() || !dry) {
      return decision;
    }
    if (hostLookup != null) {
      final SupervisorHost host = hostLookup.getIfAvailable();
      if (host != null) {
        host.wouldHaveRefused(
            supervisor.id(),
            request.command(),
            decision.requiresApproval()
                ? "would need approval: " + decision.reason()
                : decision.reason());
      }
    }
    return Decision.ALLOW;
  }

  /** The answer when a supervisor times out: a stop is allowed, anything else refused. */
  private Decision fallback(
      final ExecutionSupervisor supervisor, final ControlRequest request, final String why) {
    final boolean stop = isStop(request);
    log.atWarn()
        .addKeyValue("supervisor", supervisor.id())
        .addKeyValue("timeout", timeout)
        .log(
            stop ? "Supervisor: {}; allowing the stop" : "Supervisor: {}; refusing the command",
            why);
    return stop ? Decision.ALLOW : Decision.deny(why);
  }
}
