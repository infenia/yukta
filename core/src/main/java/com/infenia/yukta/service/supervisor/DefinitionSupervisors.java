// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import com.infenia.yukta.service.supervisor.SettingsValidationException.Issue;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import com.infenia.yukta.service.supervisor.ownership.NodeGroupIndex;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Keeps the attachments of the supervisors that sessions and workflows declare in step with their
 * definitions. The definition is the truth: a declared supervisor is attached with the source
 * {@code DEFINITION}, one that is no longer declared is detached, and attachments made by hand are
 * never touched.
 *
 * <p>Attachment ids are {@code def-} and the first 16 hex digits of the SHA-256 of the session,
 * workflow, supervisor and sorted node selectors, so reordering the declarations changes nothing
 * and a run stays supervised without a detach and attach. A declaration that cannot be attached is
 * logged, counted and tried again within a minute; it never fails the save.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.GodClass",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.UseConcurrentHashMap", // local maps that keep the declaration order
  "PMD.AvoidInstantiatingObjectsInLoops"
})
public class DefinitionSupervisors implements DefinitionUsage {

  /** Prefix of the ids of the attachments made from definitions. */
  public static final String ID_PREFIX = "def-";

  /** Log key of a session id. */
  private static final String SESSION_KEY = "sessionId";

  /** How often failed declarations are tried again. */
  private static final Duration RETRY_EVERY = Duration.ofMinutes(1);

  /** Hex digits of the hash kept in an id. */
  private static final int HASH_DIGITS = 16;

  /** Attaches and detaches. */
  private final SupervisorService service;

  /** Keeps what sessions declare for themselves as a whole. */
  private final SupervisorStore store;

  /** Knows which ids belong to supervisors the server configures itself. */
  private final SupervisorHost host;

  /** The workflows of a session. */
  private final WorkflowDefinitionStore definitions;

  /** The sessions. */
  private final SessionConfigStore sessions;

  /** The validation of declarations, kept apart from the reconciling. */
  private final DefinitionChecks checks;

  /** The last reconcile of each session in progress, so reconciles of one session run in turn. */
  private final Map<String, Mono<Void>> tails = new ConcurrentHashMap<>();

  /** Tells whether this server is the leader; null on a single server. */
  @Nullable private LeaderElection election;

  /** True when a declaration failed to attach or detach since the last retry. */
  private final AtomicBoolean anyFailed = new AtomicBoolean();

  /** The groups of the stored workflows, kept current on every reconcile. */
  @Nullable private NodeGroupIndex nodeGroups;

  /** The periodic retry; null until the server is up. */
  @Nullable private Disposable retry;

  /**
   * Creates the reconciler.
   *
   * @param service attaches and detaches
   * @param store keeps what sessions declare
   * @param host knows the server's own supervisors
   * @param definitions the workflow definitions
   * @param sessions the sessions
   */
  public DefinitionSupervisors(
      final SupervisorService service,
      final SupervisorStore store,
      final SupervisorHost host,
      final WorkflowDefinitionStore definitions,
      final SessionConfigStore sessions) {
    this.service = service;
    this.store = store;
    this.host = host;
    this.definitions = definitions;
    this.sessions = sessions;
    this.checks = new DefinitionChecks(service, host);
  }

  /** Lets the service ask who declares a supervisor before it is deleted. */
  @PostConstruct
  /* default */ void register() {
    service.setDefinitionUsage(this);
  }
  /**
   * Keeps the groups of the stored workflows current, for {@code group:} selectors.
   *
   * @param groups the index
   */
  @Autowired(required = false)
  public void setNodeGroups(final NodeGroupIndex groups) {
    this.nodeGroups = groups;
  }


  /**
   * Reconciles every session again when this server becomes the leader later.
   *
   * @param leaderElection the election
   */
  @Autowired(required = false)
  public void setLeaderElection(final LeaderElection leaderElection) {
    this.election = leaderElection;
    leaderElection.onTakeover(this::reconcileAllInBackground);
  }

  /** Reconciles every session once the server is up, on the leader only. */
  @EventListener(ApplicationReadyEvent.class)
  /* default */ void onReady() {
    reconcileAllInBackground();
    // ponytail: a fixed minute; make it a setting if operators need to tune it
    retry = Flux.interval(RETRY_EVERY, Schedulers.parallel()).subscribe(_ -> retryFailed());
  }

  /** Stops the retry. */
  @PreDestroy
  /* default */ void stop() {
    if (retry != null) {
      retry.dispose();
    }
  }

  /** Reconciles everything again if a declaration failed since the last time. */
  /* default */ void retryFailed() {
    if (anyFailed.getAndSet(false)) {
      reconcileAllInBackground();
    }
  }

  private void reconcileAllInBackground() {
    if (election != null && !election.isLeader()) {
      return;
    }
    reconcileAll()
        .doOnError(
            error -> log.atError().setCause(error).log("Could not reconcile declared supervisors"))
        .onErrorComplete()
        .subscribe();
  }

  // ---- validation

  /**
   * Checks what a session and its workflows declare: that each supervisor exists and is not the
   * server's own, that a section's selectors are valid and take a node of the workflow, and that
   * nothing is declared twice.
   *
   * @param data the session as it would be saved
   * @return the problems, each with a field such as {@code workflows.build.supervisors[0].nodes};
   *     empty when all is well
   */
  public Mono<List<Issue>> check(final SessionConfigData data) {
    return checks.check(data);
  }

  /**
   * Checks what one workflow declares, for a draft that is not saved.
   *
   * @param workflow the draft
   * @return the problems, with fields such as {@code supervisors[0].supervisor}
   */
  public Mono<List<Issue>> check(final WorkflowDefinition workflow) {
    return checks.check(workflow);
  }

  /**
   * Refuses a workflow whose declared supervisors do not check out, as a save does. A rollback
   * restores an old revision, which may name a supervisor deleted since or a node that is gone.
   *
   * @param workflow the definition about to be saved
   * @return completes when it checks out; errors with {@link SettingsValidationException}
   */
  public Mono<Void> require(final WorkflowDefinition workflow) {
    return checks.require(workflow);
  }

  /**
   * Refuses a new version of one workflow, such as a rollback, as a session save would: a missing
   * or misused supervisor, or ownership that would overlap with this version in place.
   *
   * @param sessionId the session
   * @param workflow the new version
   * @return completes when it may be saved; errors with {@link SettingsValidationException}
   */
  public Mono<Void> require(final String sessionId, final WorkflowDefinition workflow) {
    return Mono.zip(store.sessionRefs(sessionId), definitions.findAll(sessionId))
        .flatMap(
            stored -> {
              final Map<String, WorkflowDefinition> workflows =
                  new java.util.LinkedHashMap<>(stored.getT2());
              workflows.put(workflow.workflowId(), workflow);
              return checks.check(sessionId, stored.getT1(), workflows, workflow);
            })
        .flatMap(
            issues ->
                issues.isEmpty()
                    ? Mono.<Void>empty()
                    : Mono.error(new SettingsValidationException(issues)));
  }

  // ---- reconcile

  /**
   * Makes the DEFINITION attachments of a session match what its definitions declare. Reconciles of
   * one session run one after the other. Never fails: a declaration that cannot be attached is
   * logged.
   *
   * @param sessionId the session
   * @return completes when done
   */
  public Mono<Void> reconcile(final String sessionId) {
    return Mono.defer(
        () -> {
          final Sinks.Empty<Void> mine = Sinks.empty();
          final Mono<Void> mineMono = mine.asMono();
          final Mono<Void> previous = tails.put(sessionId, mineMono);
          return (previous == null ? Mono.<Void>empty() : previous)
              .then(Mono.defer(() -> doReconcile(sessionId)))
              .onErrorResume(
                  error -> {
                    log.atWarn()
                        .setCause(error)
                        .addKeyValue(SESSION_KEY, sessionId)
                        .log("Could not reconcile the supervisors a session declares");
                    return Mono.empty();
                  })
              .doFinally(
                  _ -> {
                    mine.tryEmitEmpty();
                    tails.remove(sessionId, mineMono);
                  });
        });
  }

  /**
   * Reconciles every session, and the sessions whose attachments outlived them.
   *
   * @return completes when done
   */
  public Mono<Void> reconcileAll() {
    return sessions
        .getSessionIds()
        .collect(Collectors.toSet())
        .flatMapMany(
            known ->
                Flux.concat(
                    Flux.fromIterable(known).concatMap(this::reconcile),
                    service
                        .attachments()
                        .filter(a -> a.source() == SupervisorSource.DEFINITION)
                        .mapNotNull(SupervisorAttachment::originSessionId)
                        .distinct()
                        .filter(id -> !known.contains(id))
                        // the session is gone: drop the declarations it left behind first
                        .concatMap(this::forget)))
        .then();
  }

  /**
   * Forgets what a session declared and removes its DEFINITION attachments, for when it is deleted.
   *
   * @param sessionId the session
   * @return completes when done
   */
  public Mono<Void> forget(final String sessionId) {
    final NodeGroupIndex groups = nodeGroups;
    if (groups != null) {
      groups.forget(sessionId);
    }
    return store.saveSessionRefs(sessionId, List.of()).then(reconcile(sessionId));
  }

  /**
   * Keeps what a session declares for itself as a whole, then reconciles it.
   *
   * @param data the session as saved
   * @return completes when done
   */
  public Mono<Void> apply(final SessionConfigData data) {
    return store
        .saveSessionRefs(data.sessionId(), data.supervisors())
        .then(Mono.defer(() -> reconcile(data.sessionId())));
  }

  /**
   * The supervisors a session declares for itself as a whole.
   *
   * @param sessionId the session
   * @return the declared supervisors
   */
  public Mono<List<SupervisorRef>> sessionRefs(final String sessionId) {
    return store.sessionRefs(sessionId);
  }

  private Mono<Void> doReconcile(final String sessionId) {
    return Mono.zip(
            store.sessionRefs(sessionId),
            definitions.findAll(sessionId),
            service
                .attachments()
                .filter(
                    a ->
                        a.source() == SupervisorSource.DEFINITION
                            && sessionId.equals(a.originSessionId()))
                .collectMap(SupervisorAttachment::id))
        .flatMap(
            tuple -> {
              final Map<String, SupervisorAttachment> desired =
                  desired(sessionId, tuple.getT1(), tuple.getT2());
              final Map<String, SupervisorAttachment> existing = tuple.getT3();
              final Mono<Void> detach =
                  Flux.fromIterable(existing.keySet())
                      .filter(id -> !desired.containsKey(id))
                      .concatMap(id -> detachQuietly(sessionId, id))
                      .then();
              final Mono<Void> attach =
                  Flux.fromIterable(desired.values())
                      .filter(a -> !a.equals(existing.get(a.id())))
                      .concatMap(a -> attachQuietly(sessionId, a))
                      .then();
              final NodeGroupIndex groups = nodeGroups;
              final Mono<Void> regroup =
                  groups != null && groups.put(sessionId, tuple.getT2())
                      ? service.regroup(sessionId)
                      : Mono.empty();
              return regroup.then(detach).then(attach);
            });
  }

  private Mono<Void> attachQuietly(final String sessionId, final SupervisorAttachment a) {
    return service
        .attach(a)
        .then()
        .onErrorResume(
            error -> {
              log.atWarn()
                  .setCause(error)
                  .addKeyValue("runId", SupervisorService.AUDIT_RUN)
                  .addKeyValue(SESSION_KEY, sessionId)
                  .addKeyValue("supervisor", a.supervisorId())
                  .addKeyValue("scope", a.scope().key())
                  .log("Could not attach a supervisor declared in a definition");
              failed("attach");
              return Mono.empty();
            });
  }

  /** Counts a failure and has the next retry reconcile again. */
  private void failed(final String operation) {
    anyFailed.set(true);
    Metrics.counter("yukta.supervisor.declarations.failed", "operation", operation).increment();
  }

  private Mono<Void> detachQuietly(final String sessionId, final String id) {
    return service
        .detach(id)
        .onErrorResume(
            error -> {
              log.atWarn()
                  .setCause(error)
                  .addKeyValue(SESSION_KEY, sessionId)
                  .addKeyValue("attachment", id)
                  .log("Could not detach a supervisor no definition declares any more");
              failed("detach");
              return Mono.empty();
            });
  }

  /** The attachments the declarations ask for, by id. */
  /* default */ static Map<String, SupervisorAttachment> desired(
      final String sessionId,
      final List<SupervisorRef> sessionRefs,
      final Map<String, WorkflowDefinition> workflows) {
    final Map<String, SupervisorAttachment> out = new LinkedHashMap<>();
    for (final SupervisorRef ref : sessionRefs) {
      final SupervisorAttachment a =
          new SupervisorAttachment(
              attachmentId(sessionId, "", ref),
              ref.supervisor(),
              new SupervisorScope.Session(sessionId),
              ref.runs(),
              SupervisorSource.DEFINITION,
              sessionId,
              null);
      out.put(a.id(), a);
    }
    for (final WorkflowDefinition workflow : workflows.values()) {
      for (final SupervisorRef ref : workflow.supervisors()) {
        final SupervisorScope scope = scopeOf(sessionId, workflow.workflowId(), ref);
        if (scope != null) {
          final SupervisorAttachment a =
              new SupervisorAttachment(
                  attachmentId(sessionId, workflow.workflowId(), ref),
                  ref.supervisor(),
                  scope,
                  ref.runs(),
                  SupervisorSource.DEFINITION,
                  sessionId,
                  workflow.workflowId());
          out.put(a.id(), a);
        }
      }
    }
    return out;
  }

  private static @Nullable SupervisorScope scopeOf(
      final String sessionId, final String workflowId, final SupervisorRef ref) {
    try {
      return ref.nodes() == null
          ? new SupervisorScope.Workflow(workflowId, sessionId)
          : new SupervisorScope.Section(workflowId, sessionId, Set.copyOf(ref.nodes()));
    } catch (IllegalArgumentException e) {
      log.atWarn()
          .setCause(e)
          .addKeyValue(SESSION_KEY, sessionId)
          .addKeyValue("workflowId", workflowId)
          .log("Skipped a declared supervisor whose nodes are not a valid section");
      return null;
    }
  }

  /**
   * The id of the attachment made from a declaration.
   *
   * @param sessionId the session
   * @param workflowId the workflow, or empty for a declaration of the whole session
   * @param ref the declaration
   * @return {@code def-} and 16 hex digits
   */
  public static String attachmentId(
      final String sessionId, final String workflowId, final SupervisorRef ref) {
    final String key =
        String.join("\n", sessionId, workflowId, ref.supervisor(), sorted(ref.nodes()));
    return ID_PREFIX + hash("SHA-256", key).substring(0, HASH_DIGITS);
  }

  /** The hex of the hash of a text; every Java runtime has SHA-256, so that name never fails. */
  /* default */ static String hash(final String algorithm, final String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /* default */ static String sorted(final @Nullable List<String> nodes) {
    return nodes == null ? "" : String.join(",", new TreeSet<>(nodes));
  }

  // ---- usage

  @Override
  public Mono<List<String>> declaredBy(final String supervisorId) {
    return sessions
        .getSessionIds()
        .concatMap(
            sessionId ->
                Mono.zip(store.sessionRefs(sessionId), definitions.findAll(sessionId))
                    .map(
                        tuple -> {
                          final List<String> users = new ArrayList<>();
                          if (tuple.getT1().stream()
                              .anyMatch(r -> r.supervisor().equals(supervisorId))) {
                            users.add("session " + sessionId);
                          }
                          tuple
                              .getT2()
                              .values()
                              .forEach(
                                  w -> {
                                    if (w.supervisors().stream()
                                        .anyMatch(r -> r.supervisor().equals(supervisorId))) {
                                      users.add("workflow " + sessionId + "/" + w.workflowId());
                                    }
                                  });
                          return users;
                        }))
        .flatMapIterable(users -> users)
        .collectList();
  }
}
