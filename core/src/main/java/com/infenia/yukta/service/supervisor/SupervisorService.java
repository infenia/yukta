// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.AttachmentAware;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorIds;
import com.infenia.yukta.plugin.control.SupervisorPreset;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.DirectiveSupervisor;
import com.infenia.yukta.plugin.control.engine.ReadsAware;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ControlAudit;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.supervisor.agent.AgentCredentials;
import com.infenia.yukta.service.supervisor.engine.SupervisorQueries;
import com.infenia.yukta.service.supervisor.hierarchy.SupervisorHierarchy;
import com.infenia.yukta.service.supervisor.hierarchy.SupervisorResolver;
import com.infenia.yukta.service.supervisor.ownership.OwnershipConflictException;
import com.infenia.yukta.service.supervisor.ownership.OwnershipValidator;
import com.infenia.yukta.service.supervisor.ownership.WorkflowShape;
import com.infenia.yukta.service.supervisor.workflow.WorkflowSupervisor;
import com.infenia.yukta.service.supervisor.workflow.WorkflowSupervisorChecks;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Creates, changes and removes supervisor supervisors and attaches them to a scope at run time.
 *
 * <p>A supervisor attached while runs are going sees the events from then on and reviews the
 * commands from then on, so a run is supervised from the moment of attachment, not from its start.
 * Every attach and detach is recorded in the execution log under the run id {@value #AUDIT_RUN}.
 */
@Slf4j
@Service
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.GodClass",
  "PMD.CyclomaticComplexity"
})
public class SupervisorService {

  /** Run id under which attaching and detaching is recorded. */
  public static final String AUDIT_RUN = "supervisors";

  /** Default for {@code yukta.supervisor.max-attachments}. */
  public static final int DEFAULT_MAX_ATTACHMENTS = 200;

  /** Most attachments; each is a supervisor with its own subscription and metric tag. */
  private int maxAttachments = DEFAULT_MAX_ATTACHMENTS;

  /** Attaches in progress, so the limit holds when several run at once. */
  private final AtomicInteger attaching = new AtomicInteger();

  /** Message for a section that selects a group, which runtime matching cannot resolve yet. */
  public static final String GROUPS_NOT_YET =
      "Group selectors (group:<name>) are not supported yet: list the node ids or use a prefix"
          + " such as build.*";

  /** Start of the messages about one supervisor. */
  private static final String SUPERVISOR_PREFIX = "Supervisor ";

  private static final String NOT_FOUND = "Supervisor not found: ";

  /** Most decisions one replay returns, and how long a replay waits for a timer. */
  private static final int REPLAY_LIMIT = 100;

  private static final Duration REPLAY_WAIT = Duration.ofSeconds(2);

  /** Where supervisors and attachments are kept. */
  private final SupervisorStore store;

  /** The factories by supervisor type. */
  private final Map<String, SupervisorFactory> factories;

  /** Runs the attached supervisors. */
  private final SupervisorHost host;

  /** Records attach and detach. */
  private final ExecutionLogStore executionLog;

  /** The stored workflow definitions, to match selectors against real nodes; none without it. */
  private @Nullable WorkflowDefinitionStore definitions;

  /** Attachments the last restore started. */
  private final AtomicInteger restored = new AtomicInteger();

  /** Attachments the last restore could not start. */
  private final AtomicInteger restoreFailed = new AtomicInteger();

  /** Tells whether this server is the leader; null on a single server. */
  @Nullable private LeaderElection election;

  @Nullable private AgentCredentials agentCredentials;

  /** The stored supervisors, for parent lookups; null when there is none. */
  @Nullable private SupervisorResolver resolver;

  /** Checks workflow supervisors against the stores; null where there are none. */
  @Nullable private WorkflowSupervisorChecks workflowChecks;

  /** What engines may read, looked up late: the queries need the service that needs this one. */
  @Nullable private ObjectProvider<SupervisorQueries> queries;

  /** The newest saved states of each supervisor. */
  private final SupervisorVersions history = new SupervisorVersions();

  /** Tells who declares a supervisor; null where definitions declare none. */
  @Nullable private DefinitionUsage definitionUsage;

  /** Receives every audit record besides the log, if a module provides one. */
  @Nullable private AuditSink auditSink;

  /**
   * Creates the service.
   *
   * @param store where supervisors and attachments are kept
   * @param factories the supervisor factories
   * @param host runs the attached supervisors
   * @param executionLog records attach and detach
   */
  public SupervisorService(
      final SupervisorStore store,
      final List<SupervisorFactory> factories,
      final SupervisorHost host,
      final ExecutionLogStore executionLog) {
    this.store = store;
    this.factories =
        factories.stream().collect(Collectors.toMap(SupervisorFactory::type, Function.identity()));
    this.host = host;
    this.executionLog = executionLog;
  }

  /**
   * Sets the attachment cap from {@code yukta.supervisor.max-attachments}.
   *
   * @param properties the application properties
   */
  @Autowired(required = false)
  public void setProperties(final YuktaProperties properties) {
    this.maxAttachments = properties.supervisor().maxAttachments();
  }

  /**
   * Lets deleting a supervisor that a definition declares be refused, naming who declares it.
   *
   * @param usage the lookup
   */
  public void setDefinitionUsage(final DefinitionUsage usage) {
    this.definitionUsage = usage;
  }

  /**
   * Passes every audit record on to a sink, besides the execution log.
   *
   * @param sink the sink, if the application has one
   */
  @Autowired(required = false)
  public void setAuditSink(final AuditSink sink) {
    this.auditSink = sink;
  }

  /**
   * Lets {@code workflow} supervisors be checked against the sessions and workflows.
   *
   * @param checks the checks
   */
  @Autowired(required = false)
  public void setWorkflowChecks(final WorkflowSupervisorChecks checks) {
    this.workflowChecks = checks;
  }

  /**
   * Lets composite supervisors find their children.
   *
   * @param supervisorResolver the resolver
   */
  @Autowired(required = false)
  public void setResolver(final SupervisorResolver supervisorResolver) {
    this.resolver = supervisorResolver;
  }

  /** Gives the resolver the stored supervisors before a composite is validated or started. */
  private Mono<Void> refreshResolver() {
    return resolver == null
        ? Mono.empty()
        : store.supervisors().collectList().doOnNext(resolver::refresh).then();
  }

  /**
   * Restores the attachments when this server becomes the leader later; a standby runs none.
   *
   * @param leaderElection the election
   */
  @Autowired(required = false)
  public void setLeaderElection(final LeaderElection leaderElection) {
    this.election = leaderElection;
    leaderElection.onTakeover(this::restore);
    // a demoted leader has no runs to supervise: the new leader restores the attachments
    leaderElection.onLoss(host::detachAll);
  }

  /** Supervisor changes go to the leader: a standby has no runs, and the leader would not hear. */
  private Mono<Void> leaderOnly() {
    return election != null && !election.isLeader()
        ? Mono.error(new NotLeaderException())
        : Mono.empty();
  }

  /**
   * Publishes how many attachments the last restore started and how many it could not.
   *
   * @param meters the registry
   */
  @Autowired(required = false)
  public void setMeterRegistry(final MeterRegistry meters) {
    Gauge.builder("yukta.supervisor.restored", restored, AtomicInteger::get)
        .tag("result", "started")
        .register(meters);
    Gauge.builder("yukta.supervisor.restored", restoreFailed, AtomicInteger::get)
        .tag("result", "failed")
        .register(meters);
  }

  /**
   * One owner per unit, for attachments saved before that rule: the first enabled attachment of a
   * unit (by id) keeps it, and any other that overlaps is disabled and marked {@code conflict},
   * with a WARN naming both. A failure to save a mark is logged and does not stop the restore; an
   * unreadable store is retried by the caller.
   */
  private Mono<Void> markConflicts() {
    final OwnershipValidator validator = new OwnershipValidator((s, w) -> Optional.empty());
    return store
        .attachments()
        .filter(SupervisorAttachment::enabled)
        .sort(Comparator.comparing(SupervisorAttachment::id))
        .collectList()
        .flatMapMany(
            all -> {
              final List<OwnershipValidator.Claim> kept = new ArrayList<>();
              final List<SupervisorAttachment> losers = new ArrayList<>();
              final List<OwnershipValidator.Claim> claims =
                  all.stream()
                      .map(a -> new OwnershipValidator.Claim(a.supervisorId(), a.scope()))
                      .toList();
              for (int i = 0; i < all.size(); i++) {
                final SupervisorAttachment attachment = all.get(i);
                final var claim = claims.get(i);
                final var conflicts = validator.conflicts(claim, kept);
                if (conflicts.isEmpty()) {
                  kept.add(claim);
                } else {
                  log.atWarn()
                      .addKeyValue("attachment", attachment.id())
                      .addKeyValue("conflict", conflicts.getFirst().message())
                      .log("Attachment disabled at start-up: it overlaps one that keeps the unit");
                  losers.add(attachment.asConflict());
                }
              }
              return Flux.fromIterable(losers);
            })
        .concatMap(
            marked ->
                store
                    .saveAttachment(marked)
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .setCause(error)
                              .addKeyValue("attachment", marked.id())
                              .log("Could not mark the overlapping attachment");
                          return Mono.empty();
                        }))
        .then();
  }

  /** Starts the enabled attachments that were kept, on the leader only. */
  @EventListener(ApplicationReadyEvent.class)
  /* default */ void restore() {
    if (election != null && !election.isLeader()) {
      return;
    }
    restored.set(0);
    restoreFailed.set(0);
    // not in @PostConstruct: the store may need its schema first, and an unreadable store is
    // retried
    Flux.defer(
            () -> refreshResolver().then(markConflicts()).thenMany(Flux.defer(store::attachments)))
        .retryWhen(Retry.backoff(5, Duration.ofSeconds(2)))
        .filter(SupervisorAttachment::enabled)
        .concatMap(
            attachment ->
                store
                    .supervisors()
                    .filter(supervisor -> supervisor.id().equals(attachment.supervisorId()))
                    .next()
                    // a stored row that no longer fits the rules fails its own restore (counted)
                    .doOnNext(_ -> SupervisorIds.validate(attachment.id(), "attachment id"))
                    .doOnNext(this::checkSettings)
                    .doOnNext(supervisor -> start(attachment, supervisor))
                    .doOnNext(_ -> restored.incrementAndGet())
                    .onErrorResume(
                        error -> {
                          restoreFailed.incrementAndGet();
                          log.atWarn()
                              .setCause(error)
                              .addKeyValue("attachment", attachment.id())
                              .log("Could not restore the supervisor attachment");
                          return Mono.empty();
                        }))
        .subscribe(
            _ -> {},
            error ->
                log.atError()
                    .setCause(error)
                    .log("Could not read the supervisor attachments; none were restored"));
  }

  /**
   * The supervisor types that can be used in a supervisor.
   *
   * @return the types, sorted
   */
  public List<String> types() {
    return factories.keySet().stream().sorted().toList();
  }

  /**
   * All supervisors.
   *
   * @return the supervisors
   */
  public Flux<Supervisor> supervisors() {
    return store.supervisors();
  }

  /**
   * Sets what engines may read.
   *
   * @param supervisorQueries the queries
   */
  @Autowired
  public void setQueries(final ObjectProvider<SupervisorQueries> supervisorQueries) {
    this.queries = supervisorQueries;
  }

  /**
   * The saved states of a supervisor, newest first (the newest 20 since the server started).
   *
   * @param id the supervisor id
   * @return the versions; an unknown supervisor is a {@link NotFoundException}
   */
  public Mono<List<SupervisorVersions.Version>> versions(final String id) {
    return store
        .supervisors()
        .filter(found -> found.id().equals(id))
        .next()
        .switchIfEmpty(Mono.error(new NotFoundException(NOT_FOUND + id)))
        .map(found -> history.list(id));
  }

  /**
   * What a command would have been, had the supervisor run on a past run.
   *
   * @param command the command type
   * @param executionId the run it addresses
   * @param nodeId the node, or {@code null}
   * @param cause the cause as the audit shows it
   * @param reason the reason it gave, or {@code null}
   * @param allowed whether the supervisor's capabilities would let it through
   */
  public record ReplayDecision(
      String command,
      String executionId,
      @Nullable String nodeId,
      String cause,
      @Nullable String reason,
      boolean allowed) {}

  /**
   * Tries a supervisor (its stored settings, or draft settings) on the recorded events of a past
   * run, in dry run: nothing is sent. Only statuses were recorded, so a rule on logs or input
   * requests does not fire, and a timed rule sees nothing to read.
   *
   * @param id the supervisor id
   * @param draft draft settings to try instead of the stored ones, or {@code null}
   * @param executionId the past run
   * @return what it would have done
   */
  public Mono<List<ReplayDecision>> replay(
      final String id, final @Nullable Map<String, Object> draft, final String executionId) {
    return store
        .supervisors()
        .filter(found -> found.id().equals(id))
        .next()
        .switchIfEmpty(Mono.error(new NotFoundException(NOT_FOUND + id)))
        .flatMap(
            stored -> {
              final List<ExecutionEvent> events =
                  ReplayEvents.from(executionLog.forExecution(executionId));
              if (events.isEmpty()) {
                return Mono.error(
                    new NotFoundException("No recorded events for execution " + executionId));
              }
              final Supervisor trial =
                  new Supervisor(
                      id,
                      stored.type(),
                      draft == null ? stored.settings() : draft,
                      true,
                      stored.description(),
                      stored.parents(),
                      stored.delegation());
              validateAsSaved(trial);
              final SupervisorCapabilities caps = capabilities(trial);
              final ExecutionSupervisor made = create(trial);
              final Flux<ReplayDecision> out =
                  made instanceof DirectiveSupervisor directives
                      ? directives
                          .directives(Flux.fromIterable(events))
                          .map(
                              d ->
                                  decision(
                                      d.command(),
                                      d.cause().text(),
                                      d.reason(),
                                      caps.allows(d.command())
                                          && (!d.cause().unprompted()
                                              || caps.mayInitiate(d.command()))))
                      : made.supervise(Flux.fromIterable(events))
                          .map(c -> decision(c, "event", null, caps.allows(c)));
              return out.takeUntilOther(Mono.delay(REPLAY_WAIT)).take(REPLAY_LIMIT).collectList();
            });
  }

  private static ReplayDecision decision(
      final ExecutionControlCommand command,
      final String cause,
      final @Nullable String reason,
      final boolean allowed) {
    return new ReplayDecision(
        command.getClass().getSimpleName(),
        command.executionId(),
        command.nodeId(),
        cause,
        reason,
        allowed);
  }

  /**
   * Saves an earlier state again, as a new version.
   *
   * @param id the supervisor id
   * @param version the number of the state to restore
   * @return the supervisor as restored
   */
  public Mono<Supervisor> rollback(final String id, final int version) {
    return Mono.defer(
        () ->
            history
                .find(id, version)
                .map(found -> saveSupervisor(found.supervisor()))
                .orElseGet(
                    () ->
                        Mono.error(
                            new NotFoundException(
                                "Version " + version + " of supervisor " + id + " is not kept"))));
  }

  /**
   * Creates or replaces a supervisor. Attachments that use it are restarted with the new settings.
   *
   * @param supervisor the supervisor
   * @return the saved supervisor; errors with {@link IllegalArgumentException} for an invalid id,
   *     an unknown type or invalid settings
   */
  public Mono<Supervisor> saveSupervisor(final Supervisor supervisor) {
    return leaderOnly()
        .then(refreshResolver())
        .then(
            Mono.defer(
                () ->
                    workflowChecks == null ? Mono.<Void>empty() : workflowChecks.check(supervisor)))
        .then(Mono.fromRunnable(() -> validateAsSaved(supervisor)))
        .then(checkParents(supervisor))
        // the check left the unsaved supervisor in the resolver: put the stored ones back
        .onErrorResume(error -> refreshResolver().then(Mono.error(error)))
        .then(Mono.defer(() -> store.saveSupervisor(supervisor)))
        .doOnSuccess(saved -> history.record(supervisor))
        .then(Mono.defer(this::refreshResolver))
        .then(
            store
                .attachments()
                .filter(
                    attachment ->
                        attachment.enabled() && attachment.supervisorId().equals(supervisor.id()))
                // one attachment that no longer starts must not leave the later ones on old
                // settings
                .concatMap(
                    attachment ->
                        Mono.defer(() -> restart(attachment, supervisor))
                            .onErrorResume(
                                error -> {
                                  log.atWarn()
                                      .setCause(error)
                                      .addKeyValue("attachmentId", attachment.id())
                                      .log(
                                          "Could not restart a supervisor with the saved settings");
                                  return Mono.empty();
                                }))
                .then())
        .thenReturn(supervisor);
  }

  /** A supervisor reports only to stored supervisors, in no cycle, at most four levels up. */
  private Mono<Void> checkParents(final Supervisor supervisor) {
    if (supervisor.parents().isEmpty()) {
      return Mono.empty();
    }
    return store
        .supervisors()
        .collectMap(Supervisor::id)
        .doOnNext(stored -> SupervisorHierarchy.validate(supervisor, stored))
        .then();
  }

  /** Validates the supervisor as it will be saved. */
  private void validateAsSaved(final Supervisor supervisor) {
    validate(supervisor);
  }

  /** Starts an attachment again with the saved supervisor. */
  private Mono<Void> restart(final SupervisorAttachment attachment, final Supervisor saved) {
    start(attachment, saved);
    return Mono.empty();
  }

  /**
   * Removes a supervisor that no attachment uses.
   *
   * @param id the supervisor id
   * @return completes when removed; errors with {@link NotFoundException} or {@link
   *     ConflictException} when a attachment still uses it
   */
  public Mono<Void> deleteSupervisor(final String id) {
    return refreshResolver()
        .then(Mono.defer(() -> childrenCheck(id)))
        .then(Mono.defer(() -> declaredCheck(id)))
        .then(Mono.defer(() -> deleteUnused(id)))
        .doOnSuccess(
            done -> {
              history.forget(id);
              // a supervisor created again under this id must not take the old credential
              final AgentCredentials agents = agentCredentials;
              if (agents != null) {
                agents.revoke(id);
              }
            });
  }

  /**
   * Lets a deleted supervisor's agent credential be revoked with it.
   *
   * @param credentials the agent credentials
   */
  @Autowired(required = false)
  public void setAgentCredentials(final AgentCredentials credentials) {
    this.agentCredentials = credentials;
  }

  /** Refuses with 409, naming them, when other supervisors report to this one. */
  private Mono<Void> childrenCheck(final String id) {
    return store
        .supervisors()
        .collectList()
        .flatMap(
            all -> {
              final List<String> children = SupervisorHierarchy.childrenOf(id, all);
              return children.isEmpty()
                  ? Mono.<Void>empty()
                  : Mono.error(
                      new ConflictException(
                          SUPERVISOR_PREFIX + id + " is the parent of " + children));
            });
  }

  /** Refuses with 409, naming them, when sessions or workflows declare the supervisor. */
  private Mono<Void> declaredCheck(final String id) {
    final DefinitionUsage usage = definitionUsage;
    return usage == null
        ? Mono.empty()
        : usage
            .declaredBy(id)
            .flatMap(
                users ->
                    users.isEmpty()
                        ? Mono.<Void>empty()
                        : Mono.error(
                            new ConflictException(
                                SUPERVISOR_PREFIX
                                    + id
                                    + " is declared by "
                                    + String.join(", ", users)
                                    + "; remove it from the definition first")));
  }

  private Mono<Void> deleteUnused(final String id) {
    return store
        .attachments()
        .filter(attachment -> attachment.supervisorId().equals(id))
        .hasElements()
        .flatMap(
            used ->
                used
                    ? Mono.error(
                        new ConflictException(SUPERVISOR_PREFIX + id + " is still attached"))
                    : store.deleteSupervisor(id))
        .flatMap(
            existed ->
                existed ? Mono.<Void>empty() : Mono.error(new NotFoundException(NOT_FOUND + id)));
  }

  /**
   * All attachments.
   *
   * @return the attachments
   */
  public Flux<SupervisorAttachment> attachments() {
    return store.attachments();
  }

  /**
   * Lets selectors be compared with the real nodes of a workflow when ownership is checked.
   *
   * @param workflowDefinitions the stored definitions
   */
  @Autowired(required = false)
  public void setDefinitions(final WorkflowDefinitionStore workflowDefinitions) {
    this.definitions = workflowDefinitions;
  }

  /** Whether a scope is a section with a {@code group:} selector. */
  public static boolean usesGroups(final SupervisorScope scope) {
    return scope instanceof SupervisorScope.Section section
        && section.nodes().stream().anyMatch(n -> n.startsWith(WorkflowShape.GROUP_PREFIX));
  }

  /**
   * Attaches a supervisor to a scope. An enabled attachment starts at once.
   *
   * @param attachment the attachment
   * @return the attachment; errors with {@link NotFoundException} for an unknown supervisor, {@link
   *     ConflictException} when there are the cap of attachments, or {@link
   *     IllegalArgumentException} when the supervisor's settings no longer make a supervisor
   */
  public Mono<SupervisorAttachment> attach(final SupervisorAttachment attachment) {
    try {
      SupervisorIds.validate(attachment.id(), "attachment id");
    } catch (IllegalArgumentException e) {
      return Mono.error(e);
    }
    if (usesGroups(attachment.scope())) {
      return Mono.error(new IllegalArgumentException(GROUPS_NOT_YET));
    }
    if (host.isBuiltIn(attachment.id())) {
      return Mono.error(
          new ConflictException(
              "A supervisor named '"
                  + attachment.id()
                  + "' is already configured; pick another id"));
    }
    if (election != null && !election.isLeader()) {
      return Mono.error(new NotLeaderException());
    }
    // counted before the store is read, so concurrent attaches cannot all pass the limit
    attaching.incrementAndGet();
    return attachChecked(attachment).doFinally(_ -> attaching.decrementAndGet());
  }

  private Mono<SupervisorAttachment> attachChecked(final SupervisorAttachment attachment) {
    return refreshResolver().then(Mono.defer(() -> attachResolved(attachment)));
  }

  private Mono<SupervisorAttachment> attachResolved(final SupervisorAttachment attachment) {
    return store
        .supervisors()
        .filter(supervisor -> supervisor.id().equals(attachment.supervisorId()))
        .next()
        .switchIfEmpty(Mono.error(new NotFoundException(NOT_FOUND + attachment.supervisorId())))
        .flatMap(
            supervisor ->
                store
                    .attachments()
                    .filter(existing -> !existing.id().equals(attachment.id()))
                    .count()
                    .flatMap(
                        others ->
                            others + attaching.get() - 1 >= maxAttachments
                                ? Mono.error(
                                    new ConflictException(
                                        "At most " + maxAttachments + " attachments are allowed"))
                                : Mono.just(supervisor)))
        .flatMap(supervisor -> requireFree(attachment).thenReturn(supervisor))
        .doOnNext(
            supervisor -> {
              if (attachment.enabled()) {
                start(attachment, supervisor);
              } else {
                host.detach(attachment.id());
              }
            })
        .then(
            store
                .saveAttachment(attachment)
                .onErrorResume(error -> restoreAttachment(attachment.id()).then(Mono.error(error))))
        .then(
            audited(
                true,
                "AttachSupervisor(" + attachment.id() + ", " + attachment.scope().key() + ")"))
        .thenReturn(attachment);
  }

  /**
   * Refuses an enabled attachment whose scope would give a node, a run level or the server to two
   * supervisors. Another supervisor's disabled attachment owns nothing, and neither does a Global
   * one.
   */
  private Mono<Void> requireFree(final SupervisorAttachment attachment) {
    if (!attachment.enabled()) {
      return Mono.empty();
    }
    return store
        .attachments()
        .filter(other -> other.enabled() && !other.id().equals(attachment.id()))
        .collectList()
        .flatMap(
            others ->
                shapesOf(attachment, others)
                    .map(
                        shapes ->
                            new OwnershipValidator(
                                    (session, workflow) ->
                                        Optional.ofNullable(shapes.get(session + "/" + workflow)))
                                .conflicts(
                                    new OwnershipValidator.Claim(
                                        attachment.supervisorId(), attachment.scope()),
                                    others.stream()
                                        .map(
                                            other ->
                                                new OwnershipValidator.Claim(
                                                    other.supervisorId(), other.scope()))
                                        .toList())))
        .flatMap(
            conflicts ->
                conflicts.isEmpty()
                    ? Mono.<Void>empty()
                    : Mono.<Void>error(new OwnershipConflictException(conflicts)));
  }

  /** The nodes and groups of every workflow the scopes name, so selectors match real nodes. */
  private Mono<Map<String, WorkflowShape>> shapesOf(
      final SupervisorAttachment attachment, final List<SupervisorAttachment> others) {
    final WorkflowDefinitionStore definitions = this.definitions;
    if (definitions == null) {
      return Mono.just(Map.of());
    }
    return Flux.fromStream(Stream.concat(Stream.of(attachment), others.stream()))
        .map(SupervisorAttachment::scope)
        .filter(scope -> scope.sessionId() != null && scope.workflowId() != null)
        .map(scope -> scope.sessionId() + "/" + scope.workflowId())
        .distinct()
        .concatMap(
            key -> {
              final int slash = key.indexOf('/');
              return definitions
                  .find(key.substring(0, slash), key.substring(slash + 1))
                  .map(definition -> Map.entry(key, WorkflowShape.from(definition)));
            })
        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  /** Puts the host back to what the store holds for a attachment when saving it failed. */
  private Mono<Void> restoreAttachment(final String attachmentId) {
    return store
        .attachments()
        .filter(existing -> existing.id().equals(attachmentId))
        .next()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            previous ->
                previous
                    .filter(SupervisorAttachment::enabled)
                    .map(
                        enabled ->
                            store
                                .supervisors()
                                .filter(
                                    supervisor -> supervisor.id().equals(enabled.supervisorId()))
                                .next()
                                .doOnNext(supervisor -> start(enabled, supervisor))
                                .then())
                    .orElseGet(() -> Mono.fromRunnable(() -> host.detach(attachmentId))))
        .onErrorResume(error -> Mono.empty());
  }

  /**
   * Detaches a attachment and stops its supervisor.
   *
   * @param attachmentId the attachment id
   * @return completes when stopped; errors with {@link NotFoundException}
   */
  public Mono<Void> detach(final String attachmentId) {
    return leaderOnly().then(Mono.defer(() -> detachBinding(attachmentId)));
  }

  private Mono<Void> detachBinding(final String attachmentId) {
    // The store goes first: if it fails, the attachment is still listed and still supervising,
    // not listed with nothing running.
    return store
        .deleteAttachment(attachmentId)
        .flatMap(
            existed ->
                Mono.fromRunnable(() -> host.detach(attachmentId))
                    .then(
                        existed
                            ? audited(true, "DetachSupervisor(" + attachmentId + ")")
                            : Mono.error(
                                new NotFoundException("Attachment not found: " + attachmentId))));
  }

  /**
   * Detaches every attachment limited to a session, for when the session is deleted.
   *
   * @param sessionId the session id
   * @return completes when they are gone
   */
  public Mono<Void> removeForSession(final String sessionId) {
    return store
        .attachments()
        .filter(
            attachment ->
                sessionId.equals(attachment.scope().sessionId())
                    || sessionId.equals(attachment.originSessionId()))
        .concatMap(attachment -> detachBinding(attachment.id()))
        .then();
  }

  private SupervisorFactory factory(final Supervisor supervisor) {
    final SupervisorFactory factory = factories.get(supervisor.type());
    if (factory == null) {
      throw new IllegalArgumentException(
          "Unknown supervisor type '" + supervisor.type() + "'; use one of " + types());
    }
    return factory;
  }

  private void validate(final Supervisor supervisor) {
    SupervisorIds.validate(supervisor.id(), "supervisor id");
    checkSettings(supervisor);
    factory(supervisor).validate(supervisor);
    capabilities(supervisor);
  }

  /** Refuses settings that are not in the type's schema or the common one. */
  private void checkSettings(final Supervisor supervisor) {
    SupervisorSettingsValidator.check(supervisor.settings(), factory(supervisor).getConfigSchema());
  }

  /**
   * The names of the settings of a type that hold secrets, so that reads can mask them.
   *
   * @param type the supervisor type
   * @return the names; empty for an unknown type
   */
  public Set<String> secretSettings(final String type) {
    final SupervisorFactory factory = factories.get(type);
    return factory == null
        ? Set.of()
        : factory.getConfigSchema().stream()
            .filter(field -> field.type() == ConfigField.Type.SECRET)
            .map(ConfigField::name)
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * What the catalog shows about a type.
   *
   * @param type the supervisor type
   * @return the descriptor, or empty for an unknown type
   */
  public Optional<SupervisorDescriptor> describe(final String type) {
    return Optional.ofNullable(factories.get(type)).map(SupervisorFactory::describe);
  }

  /**
   * The presets of a type.
   *
   * @param type the type
   * @return the presets, or empty for an unknown type
   */
  public Optional<List<SupervisorPreset>> presets(final String type) {
    return Optional.ofNullable(factories.get(type)).map(SupervisorFactory::presets);
  }

  /**
   * The kind of a type: {@code CONTROL_PLANE} or {@code SUPERVISOR}. It is found by making a
   * supervisor from the type's example settings; a type that cannot be made that way (it needs a
   * model, or other supervisors) is told apart by its name.
   *
   * @param type the supervisor type
   * @return the kind
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public String kind(final String type) {
    final SupervisorFactory factory = factories.get(type);
    if (factory == null) {
      return "SUPERVISOR";
    }
    try {
      final ExecutionSupervisor made =
          factory.create(
              new Supervisor("catalog-probe", type, factory.describe().exampleSettings(), true));
      return made instanceof ControlPlanePlugin ? "CONTROL_PLANE" : "SUPERVISOR";
    } catch (RuntimeException e) { // PMD.AvoidCatchingGenericException: any factory may refuse
      return kindByName(type);
    }
  }

  /** The kind of a type whose example cannot be made: told apart by its name. */
  private static String kindByName(final String type) {
    return type.endsWith("control-plane") ? "CONTROL_PLANE" : "SUPERVISOR";
  }

  /**
   * The commands a supervisor of a type may send when its settings do not say.
   *
   * @param type the supervisor type
   * @return the command names, or {@code null} for every command
   */
  public @Nullable Set<String> defaultAllowedCommands(final String type) {
    final SupervisorFactory factory = factories.get(type);
    return factory == null ? null : factory.defaultAllowedCommands();
  }

  /**
   * Checks a draft supervisor the way saving does, without keeping anything.
   *
   * @param supervisor the draft
   * @return the problems, empty when it would be accepted; a problem that names no setting has the
   *     field {@code settings}
   */
  public Mono<List<SettingsValidationException.Issue>> check(final Supervisor supervisor) {
    return refreshResolver()
        .then(
            Mono.defer(
                () ->
                    workflowChecks == null ? Mono.<Void>empty() : workflowChecks.check(supervisor)))
        .then(Mono.fromRunnable(() -> validate(supervisor)))
        .thenReturn(List.<SettingsValidationException.Issue>of())
        .onErrorResume(SettingsValidationException.class, error -> Mono.just(error.getIssues()))
        .onErrorResume(
            IllegalArgumentException.class,
            error ->
                Mono.just(
                    List.of(
                        new SettingsValidationException.Issue("settings", error.getMessage()))));
  }

  /**
   * The settings a type reads, besides the common ones.
   *
   * @param type the supervisor type
   * @return the fields, or empty for an unknown type
   */
  public List<ConfigField> configSchema(final String type) {
    final SupervisorFactory factory = factories.get(type);
    return factory == null ? List.of() : factory.getConfigSchema();
  }

  /** What the supervisor lets its supervisor do; an unknown command name is refused. */
  private SupervisorCapabilities capabilities(final Supervisor supervisor) {
    final SupervisorFactory factory = factory(supervisor);
    return SupervisorCapabilities.from(supervisor, factory.defaultAllowedCommands(supervisor));
  }

  private ExecutionSupervisor create(final Supervisor supervisor) {
    return factory(supervisor).create(supervisor);
  }

  private void start(final SupervisorAttachment attachment, final Supervisor supervisor) {
    final ExecutionSupervisor made = create(supervisor);
    if (made instanceof WorkflowSupervisor workflow) {
      workflow.bindTo(attachment.id());
    }
    if (made instanceof AttachmentAware aware) {
      aware.bindTo(attachment.id());
    }
    if (made instanceof ReadsAware reading && queries != null) {
      final SupervisorQueries reads = queries.getIfAvailable();
      if (reads != null) {
        reading.readsFrom(reads.readsFor(attachment.scope()));
      }
    }
    host.attach(
        new BoundSupervisor(
            attachment,
            made,
            supervisor.dryRun(),
            capabilities(supervisor),
            Boolean.TRUE.equals(
                supervisor.settings().get(SupervisorCapabilities.OBSERVE_INTERNAL))));
  }

  /** Records an attach or detach with the caller as actor. */
  private Mono<Void> audited(final boolean applied, final String command) {
    return Mono.deferContextual(
        context ->
            Mono.fromRunnable(
                () ->
                    ControlAudit.record(
                        executionLog,
                        auditSink,
                        new AuditRecord(
                            applied,
                            AUDIT_RUN,
                            command,
                            context.getOrDefault(ControlActor.CONTEXT_KEY, null),
                            null,
                            Instant.now()))));
  }
}
