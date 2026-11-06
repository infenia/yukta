// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.supervisor.SettingsValidationException.Issue;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Keeps the attachments of the supervisors that sessions and workflows declare in step with their
 * definitions. The definition is the truth: a declared supervisor is attached with the source
 * {@code DEFINITION}, one that is no longer declared is detached, and attachments made by hand are
 * never touched.
 *
 * <p>Attachment ids are {@code def-} and the first 16 hex digits of the SHA-256 of the session,
 * workflow, supervisor and sorted node selectors, so reordering the declarations changes nothing
 * and a run stays supervised without a detach and attach. A declaration that cannot be attached is
 * logged and left for the next reconcile; it never fails the save.
 */
@Slf4j
@Component
@SuppressWarnings({
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

  /** Hex digits of the hash kept in an id. */
  private static final int HASH_DIGITS = 16;

  /** Field name of the supervisor id in a declaration. */
  private static final String SUPERVISOR_FIELD = "supervisors[%d].supervisor";

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

  /** The last reconcile of each session in progress, so reconciles of one session run in turn. */
  private final Map<String, Mono<Void>> tails = new ConcurrentHashMap<>();

  /** Tells whether this server is the leader; null on a single server. */
  @Nullable private LeaderElection election;

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
  }

  /** Lets the service ask who declares a supervisor before it is deleted. */
  @PostConstruct
  /* default */ void register() {
    service.setDefinitionUsage(this);
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
    return service
        .supervisors()
        .map(Supervisor::id)
        .collect(Collectors.toSet())
        .map(
            known -> {
              final List<Issue> issues = new ArrayList<>();
              checkRefs(data.supervisors(), "", known, null, null, data.sessionId(), issues);
              data.workflows()
                  .forEach(
                      (key, workflow) ->
                          checkRefs(
                              workflow.supervisors(),
                              "workflows." + key + ".",
                              known,
                              workflow,
                              key,
                              data.sessionId(),
                              issues));
              return issues;
            });
  }

  /**
   * Checks what one workflow declares, for a draft that is not saved.
   *
   * @param workflow the draft
   * @return the problems, with fields such as {@code supervisors[0].supervisor}
   */
  public Mono<List<Issue>> check(final WorkflowDefinition workflow) {
    return service
        .supervisors()
        .map(Supervisor::id)
        .collect(Collectors.toSet())
        .map(
            known -> {
              final List<Issue> issues = new ArrayList<>();
              checkRefs(
                  workflow.supervisors(), "", known, workflow, workflow.workflowId(), null, issues);
              return issues;
            });
  }

  private void checkRefs(
      final List<SupervisorRef> refs,
      final String prefix,
      final Set<String> known,
      final @Nullable WorkflowDefinition workflow,
      final @Nullable String workflowId,
      final @Nullable String sessionId,
      final List<Issue> issues) {
    final Set<String> seen = new HashSet<>();
    for (int i = 0; i < refs.size(); i++) {
      final SupervisorRef ref = refs.get(i);
      final String field = prefix + SUPERVISOR_FIELD.formatted(i);
      final String nodesField = prefix + "supervisors[" + i + "].nodes";
      if (host.isBuiltIn(ref.supervisor())) {
        issues.add(new Issue(field, "is configured by the server and cannot be declared"));
      } else if (!known.contains(ref.supervisor())) {
        issues.add(new Issue(field, "no supervisor '" + ref.supervisor() + "'"));
      }
      if (workflow == null && ref.nodes() != null) {
        issues.add(new Issue(nodesField, "is not allowed in a session; declare it in a workflow"));
      } else if (workflow != null && ref.nodes() != null) {
        checkNodes(ref.nodes(), workflow, workflowId, sessionId, nodesField, issues);
      }
      if (!seen.add(ref.supervisor() + "\n" + sorted(ref.nodes()))) {
        issues.add(new Issue(field, "is declared twice with the same nodes"));
      }
    }
  }

  private static void checkNodes(
      final List<String> nodes,
      final WorkflowDefinition workflow,
      final @Nullable String workflowId,
      final @Nullable String sessionId,
      final String field,
      final List<Issue> issues) {
    try {
      final SupervisorScope.Section section =
          new SupervisorScope.Section(workflow.workflowId(), sessionId, Set.copyOf(nodes));
      final boolean any =
          workflow.nodes().stream()
              .anyMatch(node -> section.matches(sessionId, workflow.workflowId(), node.nodeId()));
      if (!any) {
        issues.add(new Issue(field, "selects no node of workflow '" + workflowId + "'"));
      }
    } catch (IllegalArgumentException e) {
      issues.add(new Issue(field, e.getMessage()));
    }
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
    return Flux.merge(
            sessions.getSessionIds(),
            service
                .attachments()
                .filter(a -> a.source() == SupervisorSource.DEFINITION)
                .mapNotNull(SupervisorAttachment::originSessionId))
        .distinct()
        .concatMap(this::reconcile)
        .then();
  }

  /**
   * Forgets what a session declared and removes its DEFINITION attachments, for when it is deleted.
   *
   * @param sessionId the session
   * @return completes when done
   */
  public Mono<Void> forget(final String sessionId) {
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
              return detach.then(attach);
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
              return Mono.empty();
            });
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
              return Mono.empty();
            });
  }

  /** The attachments the declarations ask for, by id. */
  private static Map<String, SupervisorAttachment> desired(
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
              ref.isEnabled(),
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
                  ref.isEnabled(),
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

  private static String sorted(final @Nullable List<String> nodes) {
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
