// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.AssignmentInput;
import com.infenia.yukta.mcp.dto.SupervisionAnswer;
import com.infenia.yukta.mcp.dto.SupervisorAssignmentInfo;
import com.infenia.yukta.mcp.dto.SupervisorAssignmentList;
import com.infenia.yukta.mcp.dto.SupervisorCheck;
import com.infenia.yukta.mcp.dto.SupervisorContractInfo;
import com.infenia.yukta.mcp.dto.SupervisorCredential;
import com.infenia.yukta.mcp.dto.SupervisorDeleted;
import com.infenia.yukta.mcp.dto.SupervisorDetached;
import com.infenia.yukta.mcp.dto.SupervisorDetails;
import com.infenia.yukta.mcp.dto.SupervisorListing;
import com.infenia.yukta.mcp.dto.SupervisorOwnership;
import com.infenia.yukta.mcp.dto.SupervisorPresetList;
import com.infenia.yukta.mcp.dto.SupervisorReplay;
import com.infenia.yukta.mcp.dto.SupervisorRulesDoc;
import com.infenia.yukta.mcp.dto.SupervisorSaved;
import com.infenia.yukta.mcp.dto.SupervisorTypeDetails;
import com.infenia.yukta.mcp.dto.SupervisorTypeList;
import com.infenia.yukta.mcp.dto.SupervisorVersionList;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCommonSettings;
import com.infenia.yukta.plugin.control.SupervisorContract;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.review.ControlReviewer;
import com.infenia.yukta.service.supervisor.OwnershipMap;
import com.infenia.yukta.service.supervisor.SupervisorAssignments;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorRules;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.supervisor.SupervisorSettingsMask;
import com.infenia.yukta.service.supervisor.agent.AgentCredentials;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Lists, saves and deletes supervisors, sets what they own and describes the engines, for MCP. */
@Component
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.LinguisticNaming",
  "PMD.TooManyMethods",
  "PMD.UseObjectForClearerAPI"
})
public class SupervisorProvider {

  /** What a secret is shown as; sending it back would overwrite the secret, so it is refused. */
  private static final String HIDDEN = "***";

  /** The running supervisors. */
  private final SupervisorHost host;

  /** Saves, attaches and detaches them. */
  private final SupervisorService service;

  /** The credentials of agent supervisors. */
  private final AgentCredentials credentials;

  /** Who owns each part of a workflow. */
  private final OwnershipMap ownershipMap;

  /** What supervisors own, set as a whole. */
  private final SupervisorAssignments assignments;

  /** Who carries each command. */
  private final ControlReviewer reviewer;

  /** Finds the session and workflow of a run. */
  private final ControlBusGateway controlBus;

  /**
   * The supervisors that run, the configured supervisors and the attachments.
   *
   * @return the listing
   */
  public Mono<SupervisorListing> list() {
    return Mono.zip(
            service.supervisors().collectList(),
            service.attachments().map(SupervisorProvider::info).collectList())
        .map(
            tuple ->
                new SupervisorListing(
                    host.status().stream()
                        .map(
                            s ->
                                new SupervisorListing.RunningSupervisor(
                                    s.id(),
                                    s.kind(),
                                    s.scope(),
                                    s.scopeSessionId(),
                                    s.scopeWorkflowId(),
                                    s.scopeNodes(),
                                    s.dryRun(),
                                    s.applied(),
                                    s.rejected()))
                        .toList(),
                    tuple.getT1().stream()
                        .map(
                            s ->
                                new SupervisorListing.ConfiguredSupervisor(
                                    s.id(), s.type(), s.description(), s.dryRun()))
                        .toList(),
                    tuple.getT2(),
                    service.types()));
  }

  /**
   * The supervisor types in the catalog.
   *
   * @return the types, sorted by name
   */
  public SupervisorTypeList types() {
    return new SupervisorTypeList(
        service.types().stream()
            .map(
                type -> {
                  final SupervisorDescriptor d = descriptor(type);
                  return new SupervisorTypeList.Entry(
                      type, d.displayName(), d.category().name(), d.description());
                })
            .toList());
  }

  /**
   * Describes one supervisor type with the settings it takes.
   *
   * @param type the type name
   * @return the details; an error for an unknown type
   */
  public SupervisorTypeDetails type(final String type) {
    final SupervisorDescriptor d = descriptor(type);
    final Set<String> allowed = service.defaultAllowedCommands(type);
    return new SupervisorTypeDetails(
        type,
        d.displayName(),
        d.description(),
        d.usagePattern(),
        d.category().name(),
        service.configSchema(type),
        SupervisorCommonSettings.SCHEMA,
        allowed == null ? null : allowed.stream().sorted().toList(),
        d.exampleSettings(),
        service.signals(type),
        service.canInitiate(type));
  }

  /**
   * Creates or replaces a supervisor. Attachments that use it restart with the new settings.
   *
   * @param id the supervisor id; generated when null or blank
   * @param type the supervisor type
   * @param settings the settings; none may be the hidden marker {@code ***}
   * @param dryRun whether its commands are only recorded
   * @param description what it is for
   * @return the saved supervisor without its settings; an error for an invalid one
   */
  public Mono<SupervisorSaved> save(
      final String id,
      final String type,
      final Map<String, Object> settings,
      final boolean dryRun,
      final String description) {
    return Mono.fromSupplier(
            () -> {
              final Map<String, Object> given = settings == null ? Map.of() : settings;
              if (given.containsValue(HIDDEN)) {
                throw new IllegalArgumentException(
                    "A setting is '"
                        + HIDDEN
                        + "', which is how a secret is shown; send the value");
              }
              return new Supervisor(
                  id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip(),
                  type,
                  given,
                  dryRun,
                  description == null || description.isBlank() ? null : description);
            })
        .flatMap(service::saveSupervisor)
        .map(s -> new SupervisorSaved(s.id(), s.type(), s.description(), s.dryRun()));
  }

  /**
   * What a supervisor owns.
   *
   * @param supervisorId the supervisor
   * @return its assignments
   */
  public Mono<SupervisorAssignmentList> assignments(final String supervisorId) {
    return assignments
        .of(supervisorId)
        .map(SupervisorProvider::info)
        .collectList()
        .map(list -> new SupervisorAssignmentList(supervisorId, list));
  }

  /**
   * Makes a supervisor own exactly these targets, after every check passed.
   *
   * @param supervisorId the supervisor
   * @param wanted what it should own
   * @param unlock whether locked assignments may be changed or removed
   * @return what it owns afterwards
   */
  public Mono<SupervisorAssignmentList> setAssignments(
      final String supervisorId, final List<AssignmentInput> wanted, final Boolean unlock) {
    return Mono.fromSupplier(() -> wanted(wanted))
        .flatMap(list -> assignments.set(supervisorId, list, Boolean.TRUE.equals(unlock)))
        .map(
            saved ->
                new SupervisorAssignmentList(
                    supervisorId, saved.stream().map(SupervisorProvider::info).toList()));
  }

  /**
   * What would be wrong with setting these assignments, without changing anything.
   *
   * @param supervisorId the supervisor
   * @param wanted what it should own
   * @return whether it is fine, and the problems
   */
  public Mono<SupervisorCheck> checkAssignments(
      final String supervisorId, final List<AssignmentInput> wanted) {
    return Mono.fromSupplier(() -> wanted(wanted))
        .flatMap(list -> assignments.check(supervisorId, list))
        .map(
            issues ->
                new SupervisorCheck(
                    issues.isEmpty(),
                    issues.stream().map(i -> i.field() + ": " + i.message()).toList()));
  }

  /**
   * One configured supervisor with its settings (secrets hidden), parents and assignments.
   *
   * @param id the supervisor id
   * @return the supervisor; an error for an unknown one
   */
  public Mono<SupervisorDetails> details(final String id) {
    return service
        .supervisors()
        .filter(s -> s.id().equals(id))
        .next()
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Unknown supervisor: " + id)))
        .zipWith(Mono.defer(() -> assignments.of(id).map(SupervisorProvider::info).collectList()))
        .map(
            pair -> {
              final Supervisor supervisor = pair.getT1();
              final Set<String> secrets =
                  service.configSchema(supervisor.type()).stream()
                      .filter(f -> f.type() == ConfigField.Type.SECRET)
                      .map(ConfigField::name)
                      .collect(java.util.stream.Collectors.toSet());
              return new SupervisorDetails(
                  supervisor.id(),
                  supervisor.type(),
                  supervisor.description(),
                  supervisor.dryRun(),
                  SupervisorSettingsMask.hide(supervisor.settings(), secrets),
                  supervisor.parents(),
                  pair.getT2());
            });
  }

  /**
   * Deletes a supervisor that owns nothing and that no definition or child names.
   *
   * @param id the supervisor id
   * @return the result; an error when something still uses it
   */
  public Mono<SupervisorDeleted> delete(final String id) {
    return service.deleteSupervisor(id).thenReturn(new SupervisorDeleted(id, true));
  }

  /**
   * Who may send which command to a node, a run or the server.
   *
   * @param sessionId the session, or null
   * @param workflowId the workflow, or null
   * @param executionId a run, whose session and workflow are used, or null
   * @param nodeId the node, or null for the run level
   * @return one answer per command
   */
  public Mono<SupervisionAnswer> supervision(
      final @Nullable String sessionId,
      final @Nullable String workflowId,
      final @Nullable String executionId,
      final @Nullable String nodeId) {
    return Mono.fromSupplier(
        () -> {
          String session = sessionId;
          String workflow = workflowId;
          if (executionId != null) {
            final WorkflowProgress progress = controlBus.getCurrentProgress(executionId);
            if (progress != null) {
              session = progress.sessionId();
              workflow = progress.workflowId();
            }
          }
          return new SupervisionAnswer(
              reviewer.capabilities(session, workflow, nodeId).entrySet().stream()
                  .map(
                      e ->
                          new SupervisionAnswer.Command(
                              e.getKey(),
                              e.getValue().allowed(),
                              e.getValue().via(),
                              e.getValue().reason()))
                  .toList());
        });
  }

  private static List<SupervisorAssignments.Assignment> wanted(
      final @Nullable List<AssignmentInput> wanted) {
    if (wanted == null) {
      throw new IllegalArgumentException("assignments is required (an empty list owns nothing)");
    }
    return wanted.stream()
        .map(
            a -> {
              final String target = a.target();
              if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("each assignment needs a target");
              }
              final Boolean enabled = a.enabled();
              return new SupervisorAssignments.Assignment(
                  SupervisorScope.parse(target),
                  enabled == null || enabled,
                  Boolean.TRUE.equals(a.locked()));
            })
        .toList();
  }

  private SupervisorDescriptor descriptor(final String type) {
    return service
        .describe(type)
        .orElseThrow(() -> new IllegalArgumentException("Unknown supervisor type: " + type));
  }

  private static SupervisorAssignmentInfo info(final SupervisorAttachment attachment) {
    return new SupervisorAssignmentInfo(
        attachment.id(),
        attachment.supervisorId(),
        attachment.scope().key(),
        attachment.enabled(),
        attachment.source().name(),
        attachment.locked());
  }

  /**
   * The ready-made settings of a type.
   *
   * @param type the type
   * @return its presets
   */
  public Mono<SupervisorPresetList> presets(final String type) {
    return Mono.fromSupplier(
        () ->
            new SupervisorPresetList(
                type,
                service
                    .presets(type)
                    .orElseThrow(
                        () -> new IllegalArgumentException("Unknown supervisor type: " + type))
                    .stream()
                    .map(
                        p ->
                            new SupervisorPresetList.Preset(
                                p.name(), p.description(), p.settings()))
                    .toList()));
  }

  /**
   * The saved states of a supervisor, without their settings.
   *
   * @param id the supervisor
   * @return the versions, newest first
   */
  public Mono<SupervisorVersionList> versions(final String id) {
    return service
        .versions(id)
        .map(
            found ->
                new SupervisorVersionList(
                    id,
                    found.stream()
                        .map(
                            v ->
                                new SupervisorVersionList.Version(
                                    v.version(),
                                    v.savedAt().toString(),
                                    v.supervisor().type(),
                                    v.supervisor().dryRun(),
                                    v.supervisor().settings().keySet().stream().sorted().toList()))
                        .toList()));
  }

  /**
   * Makes an earlier state current again.
   *
   * @param id the supervisor
   * @param version the number of the state
   * @return the supervisor as restored
   */
  public Mono<SupervisorSaved> rollback(final String id, final int version) {
    return service
        .rollback(id, version)
        .map(s -> new SupervisorSaved(s.id(), s.type(), s.description(), s.dryRun()));
  }

  /**
   * Tries a supervisor on a past run in dry run.
   *
   * @param id the supervisor
   * @param executionId the past run
   * @param settings draft settings, or {@code null} for the stored ones
   * @return what it would have sent
   */
  public Mono<SupervisorReplay> replay(
      final String id, final String executionId, final Map<String, Object> settings) {
    return service.replay(id, settings, executionId).map(found -> replayOf(id, executionId, found));
  }

  private static SupervisorReplay replayOf(
      final String id,
      final String executionId,
      final List<SupervisorService.ReplayDecision> found) {
    return new SupervisorReplay(
        id,
        executionId,
        found.stream()
            .map(
                d ->
                    new SupervisorReplay.Decision(
                        d.command(),
                        d.executionId(),
                        d.nodeId(),
                        d.cause(),
                        d.reason(),
                        d.allowed()))
            .toList());
  }

  /**
   * The rules document of a rules supervisor, secrets hidden.
   *
   * @param id the supervisor
   * @return the document and the newest version
   */
  public Mono<SupervisorRulesDoc> rules(final String id) {
    return SupervisorRules.read(service, id).map(doc -> rulesDoc(id, doc));
  }

  /**
   * Replaces the rules document of a rules supervisor; its other settings stay.
   *
   * @param id the supervisor
   * @param rules the new document; a secret sent as {@code ***} is kept
   * @return the document as saved
   */
  public Mono<SupervisorRulesDoc> saveRules(final String id, final Map<String, Object> rules) {
    return SupervisorRules.save(service, id, rules == null ? Map.of() : rules)
        .map(doc -> rulesDoc(id, doc));
  }

  /**
   * Tries a draft rules document on a past run in dry run, with the stored common settings.
   *
   * @param id the supervisor
   * @param executionId the past run
   * @param rules the draft document, or {@code null} for the stored rules
   * @return what the rules would have sent
   */
  public Mono<SupervisorReplay> replayRules(
      final String id, final String executionId, final Map<String, Object> rules) {
    return SupervisorRules.replay(service, id, executionId, rules)
        .map(found -> replayOf(id, executionId, found));
  }

  private SupervisorRulesDoc rulesDoc(final String id, final SupervisorRules.Document doc) {
    return new SupervisorRulesDoc(
        id,
        doc.version(),
        SupervisorSettingsMask.hide(doc.rules(), service.secretSettings(SupervisorRules.TYPE)));
  }

  /**
   * Makes the credential of an {@code http} supervisor; an earlier one stops working.
   *
   * @param id the supervisor
   * @param ttlHours hours it works, or {@code null} for no expiry
   * @return the token, shown once
   */
  public Mono<SupervisorCredential> issueCredential(final String id, final Integer ttlHours) {
    if (ttlHours != null && ttlHours < 1) {
      return Mono.error(new IllegalArgumentException("ttlHours must be at least 1"));
    }
    return service
        .supervisors()
        .filter(found -> found.id().equals(id))
        .next()
        .switchIfEmpty(Mono.error(new IllegalArgumentException("Unknown supervisor: " + id)))
        .map(
            found -> {
              if (!"http".equals(found.type())) {
                throw new ConflictException("Only a supervisor of type http has a credential");
              }
              final AgentCredentials.Issued issued =
                  credentials.issue(id, ttlHours == null ? null : Duration.ofHours(ttlHours));
              final java.time.Instant expiresAt = issued.expiresAt();
              return new SupervisorCredential(
                  id, issued.token(), expiresAt == null ? null : expiresAt.toString());
            });
  }

  /**
   * Cuts the credential of a supervisor at once.
   *
   * @param id the supervisor
   * @return the supervisor id
   */
  public Mono<SupervisorDetached> revokeCredential(final String id) {
    if (!credentials.revoke(id)) {
      return Mono.error(new IllegalArgumentException("Supervisor " + id + " has no credential"));
    }
    return Mono.just(new SupervisorDetached(id, true));
  }

  /**
   * The supervisor contract.
   *
   * @return its version and the names of its operations
   */
  public SupervisorContractInfo contract() {
    return new SupervisorContractInfo(
        SupervisorContract.VERSION,
        SupervisorContract.operations().entrySet().stream()
            .flatMap(e -> e.getValue().stream().map(op -> e.getKey() + ":" + op))
            .toList());
  }

  /**
   * Who owns the run level and each node of a workflow.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return the owners
   */
  public Mono<SupervisorOwnership> ownership(final String sessionId, final String workflowId) {
    return ownershipMap
        .ownersOf(sessionId, workflowId)
        .map(
            found ->
                new SupervisorOwnership(
                    found.sessionId(),
                    found.workflowId(),
                    found.defaultSupervisor(),
                    found.run(),
                    found.nodes()));
  }
}
