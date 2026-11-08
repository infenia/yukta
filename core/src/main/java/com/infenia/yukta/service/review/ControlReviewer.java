// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.Delegation;
import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.supervisor.hierarchy.DelegationChain;
import com.infenia.yukta.service.supervisor.hierarchy.SupervisorResolver;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Decides whether a control command may run: a person's command goes to the one owner of its unit
 * (and the owner's parents for an approval), an engine's or the system's is allowed here.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
public class ControlReviewer {

  /** The {@code via} of a unit that nobody owns. */
  public static final String DEFAULT_VIA = "default";

  /**
   * Reactor context key of an {@code AtomicReference<String>} that a caller may put in to learn
   * which supervisor carried a person's command: its id, or {@link #DEFAULT_VIA}.
   */
  public static final String VIA_KEY = "yukta.control.via";

  /** A unit has one owner; saved data with more is still reviewed, by the lowest id. */
  private static final int OWNERS_PER_UNIT = 1;

  /** The stored supervisors, for their parents; none without it. */
  private @Nullable SupervisorResolver hierarchy;

  /** The supervisors attached at run time; set by Spring, empty without it. */
  private AttachedSupervisors attached = new AttachedSupervisors();

  /** Finds the host, to record what a dry-run supervisor would have refused. */
  private @Nullable ObjectProvider<DryRunRecorder> hostLookup;

  /** Whether people may control what nobody owns ({@code yukta.supervisor.default.enabled}). */
  private boolean defaultEnabled = true;

  /** Whether every supervisor only records ({@code yukta.supervisor.dry-run}). */
  private boolean dryRun;

  /** Longest a single supervisor may take to answer; a hung review must not block a run. */
  private final Duration timeout;

  /**
   * Create the reviewer.
   *
   * @param supervisors bean supervisors; they own nothing, so they are no longer asked (kept so
   *     callers need no change)
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
   * @param supervisors bean supervisors; they own nothing, so they are no longer asked (kept so
   *     callers need no change)
   * @param timeout longest a supervisor may take to answer
   */
  public ControlReviewer(
      final ObjectProvider<ExecutionSupervisor> supervisors, final Duration timeout) {
    this.timeout = timeout;
  }

  /**
   * Includes the supervisors attached at run time in reviews.
   *
   * @param attachedSupervisors the set
   */
  @Autowired
  public void setAttachedSupervisors(final AttachedSupervisors attachedSupervisors) {
    this.attached = attachedSupervisors;
  }

  /**
   * Lets an owner's need for approval go to its parents first.
   *
   * @param resolver the stored supervisors
   */
  @Autowired(required = false)
  public void setHierarchy(final SupervisorResolver resolver) {
    this.hierarchy = resolver;
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
      final ObjectProvider<DryRunRecorder> host, final YuktaProperties properties) {
    this.hostLookup = host;
    this.dryRun = properties.supervisor().dryRun();
    this.defaultEnabled = properties.supervisor().defaultSupervisor().enabled();
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
                  case ReviewVerdict.Controlled controlled ->
                      Mono.<Void>error(
                          new ControlledBySupervisorException(
                              controlled.supervisorId(), controlled.reason()));
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
          final Optional<BoundSupervisor> owner = owner(request);
          final boolean operator =
              ControlActor.Channel.OPERATOR
                  .name()
                  .equals(context.getOrDefault(ControlActor.CHANNEL_KEY, null));
          if (!operator) {
            // an engine's own command was scope-checked by the host, and the server's own
            // housekeeping is never reviewed
            return Mono.just(ReviewVerdict.ALLOWED);
          }
          via(context, owner.map(bound -> bound.attachment().supervisorId()).orElse(DEFAULT_VIA));
          if (owner.isEmpty()) {
            return Mono.just(
                defaultEnabled
                    ? ReviewVerdict.ALLOWED
                    : new ReviewVerdict.Controlled(
                        null, "Nothing owns this and the default supervisor is off"));
          }
          final BoundSupervisor bound = owner.get();
          if (!bound.capabilities().allowsOperator(request.command())) {
            final String name = bound.attachment().supervisorId();
            return Mono.just(
                new ReviewVerdict.Controlled(
                    name, name + " controls this. People cannot do this directly."));
          }
          return ask(List.of(bound), request, satisfied, byPerson)
              .flatMap(verdict -> delegated(bound, request, verdict));
        });
  }

  /**
   * An owner's need for approval goes to its parents first, in the mode set for approvals. One that
   * allows it lets the command through, one that refuses ends it, and when no parent decides (none,
   * all silent, or a {@code HIGH} risk, which only a person approves) the need stays for a person.
   */
  private static void via(final reactor.util.context.ContextView context, final String carrier) {
    context
        .<java.util.concurrent.atomic.AtomicReference<String>>getOrEmpty(VIA_KEY)
        .ifPresent(holder -> holder.set(carrier));
  }

  private Mono<ReviewVerdict> delegated(
      final BoundSupervisor owner, final ControlRequest request, final ReviewVerdict verdict) {
    if (!(verdict instanceof ReviewVerdict.NeedsApproval needs)
        || hierarchy == null
        || needs.need().risk() == InputRisk.HIGH) {
      return Mono.just(verdict);
    }
    final Optional<Supervisor> shell = hierarchy.supervisor(owner.attachment().supervisorId());
    if (shell.isEmpty() || shell.get().parents().isEmpty()) {
      return Mono.just(verdict);
    }
    final Delegation how = shell.get().delegationFor(DelegationKind.APPROVAL);
    return DelegationChain.resolve(how, shell.get().parents(), parent -> askParent(parent, request))
        .map(
            outcome ->
                switch (outcome.kind()) {
                  case ALLOWED -> ReviewVerdict.ALLOWED;
                  case REFUSED -> new ReviewVerdict.Denied(outcome.by() + ": " + outcome.reason());
                  case PERSON ->
                      new ReviewVerdict.NeedsApproval(
                          needs.need(),
                          needs.reason() + " (asked " + outcome.asked() + ")",
                          needs.askedBy());
                });
  }

  /** One parent's answer: its own review of the command, if it runs; silent if it does not. */
  private Mono<DelegationChain.Reply> askParent(final String parent, final ControlRequest request) {
    return attachedSupervisors()
        .filter(bound -> bound.attachment().supervisorId().equals(parent))
        .findFirst()
        .map(bound -> decide(bound, request))
        .orElseGet(Mono::empty)
        .map(
            decision ->
                decision.allowed()
                    ? DelegationChain.Reply.allow()
                    : decision.requiresApproval()
                        ? DelegationChain.Reply.noOpinion()
                        : DelegationChain.Reply.deny(
                            Objects.requireNonNullElse(decision.reason(), "refused")));
  }

  /**
   * Asks the given supervisors in order and combines their answers: the first refusal ends the
   * review and wins over any need for approval, and several needs merge into one. Production asks
   * the owner only; the same pipeline will ask a chain of parents when delegation arrives.
   *
   * @param reviewers who to ask, in order
   * @param request the command and who sent it
   * @param satisfied the supervisors whose approval request for this command was approved
   * @param byPerson whether a person (not a supervisor) sent the command
   * @return the combined verdict
   */
  protected Mono<ReviewVerdict> ask(
      final List<? extends ExecutionSupervisor> reviewers,
      final ControlRequest request,
      final Set<String> satisfied,
      final boolean byPerson) {
    return Flux.fromIterable(reviewers)
        .concatMap(
            supervisor ->
                decide(supervisor, request)
                    .map(decision -> new ReviewVerdicts.Vote(supervisor.id(), decision)))
        .filter(vote -> !vote.decision().allowed() && !vote.satisfiedBy(satisfied))
        .takeUntil(vote -> !vote.decision().requiresApproval())
        .collectList()
        .map(votes -> ReviewVerdicts.combine(request, votes, byPerson));
  }

  /** The supervisors attached at run time, for a reviewer that selects differently. */
  protected final java.util.stream.Stream<BoundSupervisor> attachedSupervisors() {
    return attached.stream();
  }

  /**
   * Whether a unit that nobody owns is carried by the default supervisor.
   *
   * @return true if it is
   */
  public boolean defaultSupervisorEnabled() {
    return defaultEnabled;
  }

  /**
   * What a person may do with a unit, without sending anything: who carries the command and whether
   * it is allowed. The owner's own review is not asked, so an owner that lists the command may
   * still refuse it when it is sent.
   *
   * @param sessionId the session, or {@code null} for the server
   * @param workflowId the workflow, or {@code null} for the server
   * @param nodeId the node, or {@code null} for the run level or the server
   * @param command a command of the kind asked about
   * @return who carries it and whether people may send it
   */
  public Capability capability(
      final @Nullable String sessionId,
      final @Nullable String workflowId,
      final @Nullable String nodeId,
      final ExecutionControlCommand command) {
    final Optional<BoundSupervisor> owner =
        owner(new ControlRequest(command, null, sessionId, workflowId, nodeId));
    if (owner.isEmpty()) {
      return defaultEnabled
          ? new Capability(true, DEFAULT_VIA, null)
          : new Capability(
              false, DEFAULT_VIA, "Nothing owns this and the default supervisor is off");
    }
    final String name = owner.get().attachment().supervisorId();
    return owner.get().capabilities().allowsOperator(command)
        ? new Capability(true, name, null)
        : new Capability(false, name, name + " controls this. People cannot do this directly.");
  }

  /**
   * What a caller should know about supervision before acting: whether the default supervisor
   * carries what nobody owns, and how many supervisors own something the caller can reach.
   *
   * @param defaultSupervisor whether people can control what nobody owns
   * @param owners the attached supervisors that own something in reach
   */
  public record Summary(boolean defaultSupervisor, int owners) {}

  /**
   * The supervision summary for a caller.
   *
   * @param sessions the sessions the caller is limited to, or {@code null} for all
   * @return the summary
   */
  public Summary summary(final @Nullable List<String> sessions) {
    final int owners =
        (int)
            attached.stream()
                .filter(bound -> !(bound.scope() instanceof SupervisorScope.Global))
                .filter(
                    bound ->
                        sessions == null
                            || bound.scope().sessionId() != null
                                && sessions.contains(bound.scope().sessionId()))
                .map(bound -> bound.attachment().supervisorId())
                .distinct()
                .count();
    return new Summary(defaultEnabled, owners);
  }

  /**
   * Who carries a command and whether people may send it.
   *
   * @param allowed whether people may send it
   * @param via the owning supervisor, or {@code default} for the default supervisor
   * @param reason why not, or {@code null}
   */
  public record Capability(boolean allowed, String via, @Nullable String reason) {}

  /**
   * The one supervisor that owns what a command is about: the attached supervisor whose scope takes
   * the command's node (or its run level, or the server for a server-level command), by {@link
   * SupervisorScope#owns}. Global scope owns nothing, and neither does any scope when the run could
   * not be looked up. If saved data ever has two owners, the one with the lowest id answers, and
   * the choice is stable.
   */
  private Optional<BoundSupervisor> owner(final ControlRequest request) {
    final List<BoundSupervisor> owners =
        attached.stream()
            .filter(
                bound ->
                    bound
                        .scope()
                        .owns(
                            request.command(),
                            request.sessionId(),
                            request.workflowId(),
                            request.nodeId()))
            .sorted(Comparator.comparing(BoundSupervisor::id))
            .toList();
    if (owners.size() > OWNERS_PER_UNIT) {
      log.atWarn()
          .addKeyValue("owners", owners.stream().map(BoundSupervisor::id).toList())
          .log("More than one supervisor owns the same unit; the first answers");
    }
    return owners.stream().findFirst();
  }

  /** One supervisor's answer, within the time limit; an error or nothing answers as a timeout. */
  private Mono<Decision> decide(
      final ExecutionSupervisor supervisor, final ControlRequest request) {
    return Mono.defer(() -> supervisor.review(request))
        .timeout(
            timeout, Mono.fromSupplier(() -> fallback(supervisor, request, "review timed out")))
        .switchIfEmpty(
            Mono.fromSupplier(() -> fallback(supervisor, request, "review returned nothing")))
        .onErrorResume(
            error -> {
              log.atWarn()
                  .setCause(error)
                  .addKeyValue("supervisor", supervisor.id())
                  .log("Supervisor review failed");
              return Mono.just(fallback(supervisor, request, "review failed"));
            })
        .map(decision -> dryRunned(supervisor, request, decision));
  }

  /** A refusal or a need for approval from a dry-run supervisor is recorded, not enforced. */
  private Decision dryRunned(
      final ExecutionSupervisor supervisor, final ControlRequest request, final Decision decision) {
    final boolean dry = dryRun || supervisor instanceof BoundSupervisor bound && bound.dryRun();
    if (decision.allowed() || !dry) {
      return decision;
    }
    if (hostLookup != null) {
      final DryRunRecorder host = hostLookup.getIfAvailable();
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
    final boolean stop = ReviewVerdicts.isStop(request);
    log.atWarn()
        .addKeyValue("supervisor", supervisor.id())
        .addKeyValue("timeout", timeout)
        .log(
            stop ? "Supervisor: {}; allowing the stop" : "Supervisor: {}; refusing the command",
            why);
    return stop ? Decision.ALLOW : Decision.deny(why);
  }
}
