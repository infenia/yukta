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
import com.infenia.yukta.service.supervisor.SettingsValidationException.Issue;
import com.infenia.yukta.service.supervisor.ownership.OwnershipValidator;
import com.infenia.yukta.service.supervisor.ownership.WorkflowShape;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Checks what sessions and workflows declare for their supervisors: that each one exists and is not
 * the server's own, that the selectors take nodes of the workflow, that nothing is declared twice,
 * and that no two supervisors claim the same node.
 */
public final class DefinitionChecks {

  /** Prefix of the problem fields of a workflow. */
  private static final String WORKFLOWS = "workflows.";

  /** Field name of the supervisor id in a declaration. */
  private static final String SUPERVISOR_FIELD = "supervisors[%d].supervisor";

  /** Attaches and detaches, to read what is attached by hand. */
  private final SupervisorService service;

  /** Knows which ids belong to supervisors the server configures itself. */
  private final SupervisorHost host;

  /**
   * Creates the checks.
   *
   * @param service attaches and detaches, to read what is attached by hand
   * @param host knows the server's own supervisors
   */
  public DefinitionChecks(final SupervisorService service, final SupervisorHost host) {
    this.service = service;
    this.host = host;
  }

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
                              WORKFLOWS + key + ".",
                              known,
                              workflow,
                              key,
                              data.sessionId(),
                              issues));
              return issues;
            })
        .flatMap(
            issues ->
                issues.isEmpty()
                    ? ownershipIssues(data.sessionId(), data.supervisors(), data.workflows())
                    : Mono.just(issues));
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

  /**
   * Checks one workflow as part of its session, for a save that changes only that workflow (a
   * rollback to a saved version): the references, then ownership across the whole session with this
   * version in place, as a session save does.
   *
   * @param sessionId the session
   * @param sessionRefs what the session itself declares
   * @param workflows the stored workflows of the session, with the new version in place
   * @param workflow the new version
   * @return the problems, empty when the save may go ahead
   */
  public Mono<List<Issue>> check(
      final String sessionId,
      final List<SupervisorRef> sessionRefs,
      final Map<String, WorkflowDefinition> workflows,
      final WorkflowDefinition workflow) {
    return check(workflow)
        .flatMap(
            issues ->
                issues.isEmpty()
                    ? ownershipIssues(sessionId, sessionRefs, workflows)
                    : Mono.just(issues));
  }

  /**
   * Refuses a workflow whose declared supervisors do not check out, as a save does. A rollback
   * restores an old revision, which may name a supervisor deleted since or a node that is gone.
   *
   * @param workflow the definition about to be saved
   * @return completes when it checks out; errors with {@link SettingsValidationException}
   */
  public Mono<Void> require(final WorkflowDefinition workflow) {
    return check(workflow)
        .flatMap(
            issues ->
                issues.isEmpty()
                    ? Mono.<Void>empty()
                    : Mono.error(new SettingsValidationException(issues)));
  }

  /**
   * Where the saved definitions would give one node, run level or the server to two supervisors:
   * what the session declares against the attachments others made by hand (or from other sessions),
   * and those attachments against the nodes and groups of the new definitions.
   */
  private Mono<List<Issue>> ownershipIssues(
      final String sessionId,
      final List<SupervisorRef> sessionRefs,
      final Map<String, WorkflowDefinition> workflows) {
    final OwnershipValidator validator =
        new OwnershipValidator(
            (session, workflow) ->
                session != null && !session.equals(sessionId)
                    ? Optional.empty()
                    : Optional.ofNullable(workflows.get(workflow)).map(WorkflowShape::from));
    return service
        .attachments()
        .filter(
            a ->
                a.enabled()
                    && !(a.source() == SupervisorSource.DEFINITION
                        && sessionId.equals(a.originSessionId())))
        .collectList()
        .map(
            kept -> {
              final List<Issue> issues = new ArrayList<>();
              final List<OwnershipValidator.Claim> claims =
                  new ArrayList<>(
                      kept.stream()
                          .map(a -> new OwnershipValidator.Claim(a.supervisorId(), a.scope()))
                          .toList());
              final Map<String, SupervisorAttachment> declared =
                  DefinitionSupervisors.desired(sessionId, sessionRefs, workflows);
              declared.values().stream()
                  .filter(SupervisorAttachment::enabled)
                  .forEach(
                      a -> {
                        final OwnershipValidator.Claim claim =
                            new OwnershipValidator.Claim(a.supervisorId(), a.scope());
                        validator
                            .conflicts(claim, claims)
                            .forEach(
                                c ->
                                    issues.add(
                                        new Issue(
                                            fieldOf(a, sessionRefs, workflows), c.message())));
                        claims.add(claim);
                      });
              final List<OwnershipValidator.Claim> keptClaims =
                  kept.stream()
                      .map(o -> new OwnershipValidator.Claim(o.supervisorId(), o.scope()))
                      .toList();
              for (int i = 0; i < kept.size(); i++) {
                final SupervisorAttachment a = kept.get(i);
                validator
                    .conflicts(keptClaims.get(i), keptClaims.subList(i + 1, keptClaims.size()))
                    .forEach(
                        c ->
                            issues.add(
                                new Issue(
                                    a.scope().workflowId() == null
                                        ? "supervisors"
                                        : WORKFLOWS + a.scope().workflowId() + ".nodes",
                                    c.message())));
              }
              return issues;
            });
  }

  /** The field of a declaration, such as {@code workflows.build.supervisors[0].supervisor}. */
  /* default */ static String fieldOf(
      final SupervisorAttachment attachment, final SessionConfigData data) {
    return fieldOf(attachment, data.supervisors(), data.workflows());
  }

  private static String fieldOf(
      final SupervisorAttachment attachment,
      final List<SupervisorRef> sessionRefs,
      final Map<String, WorkflowDefinition> workflows) {
    final String workflowId = attachment.originWorkflowId();
    final List<SupervisorRef> refs =
        workflowId == null ? sessionRefs : workflows.get(workflowId).supervisors();
    for (int i = 0; i < refs.size(); i++) {
      if (DefinitionSupervisors.attachmentId(
              attachment.originSessionId(), workflowId == null ? "" : workflowId, refs.get(i))
          .equals(attachment.id())) {
        return (workflowId == null ? "" : WORKFLOWS + workflowId + ".")
            + SUPERVISOR_FIELD.formatted(i);
      }
    }
    return workflowId == null ? "supervisors" : WORKFLOWS + workflowId + ".supervisors";
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
      if (!seen.add(ref.supervisor() + "\n" + DefinitionSupervisors.sorted(ref.nodes()))) {
        issues.add(new Issue(field, "is declared twice with the same nodes"));
      }
    }
  }

  /** The nodes the {@code group:} selectors name in this version of the workflow. */
  private static Set<String> groupMembers(
      final List<String> selectors, final WorkflowDefinition workflow) {
    final WorkflowShape shape = WorkflowShape.from(workflow);
    final Set<String> members = new HashSet<>();
    selectors.stream()
        .filter(selector -> selector.startsWith(WorkflowShape.GROUP_PREFIX))
        .forEach(selector -> members.addAll(shape.take(selector)));
    return members;
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
          new SupervisorScope.Section(workflow.workflowId(), sessionId, Set.copyOf(nodes))
              .withGroupMembers(groupMembers(nodes, workflow));
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
}
