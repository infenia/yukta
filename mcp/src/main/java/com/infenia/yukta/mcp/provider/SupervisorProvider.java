// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.SupervisorAttachmentInfo;
import com.infenia.yukta.mcp.dto.SupervisorContractInfo;
import com.infenia.yukta.mcp.dto.SupervisorCredential;
import com.infenia.yukta.mcp.dto.SupervisorDetached;
import com.infenia.yukta.mcp.dto.SupervisorListing;
import com.infenia.yukta.mcp.dto.SupervisorOwnership;
import com.infenia.yukta.mcp.dto.SupervisorPresetList;
import com.infenia.yukta.mcp.dto.SupervisorReplay;
import com.infenia.yukta.mcp.dto.SupervisorSaved;
import com.infenia.yukta.mcp.dto.SupervisorTypeDetails;
import com.infenia.yukta.mcp.dto.SupervisorTypeList;
import com.infenia.yukta.mcp.dto.SupervisorVersionList;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCommonSettings;
import com.infenia.yukta.plugin.control.SupervisorContract;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.supervisor.OwnershipMap;
import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.supervisor.agent.AgentCredentials;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Lists, saves, attaches and detaches supervisors, and describes their types, for MCP tools. */
@Component
@RequiredArgsConstructor
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
  private final OwnershipMap ownership;

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
                      type,
                      d.displayName(),
                      d.category().name(),
                      service.kind(type),
                      d.description());
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
        service.kind(type),
        service.configSchema(type),
        SupervisorCommonSettings.SCHEMA,
        allowed == null ? null : allowed.stream().sorted().toList(),
        d.exampleSettings());
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
   * Attaches a supervisor to a scope; the attachment is MANUAL.
   *
   * @param supervisorId an existing supervisor
   * @param scope {@code global}, {@code session:<id>}, {@code workflow:<id>}, {@code
   *     workflow:<sessionId>/<workflowId>} or a section {@code workflow:<sessionId>/<workflowId>//
   *     <selector>,<selector>}
   * @param enabled whether it runs; null means yes
   * @param attachmentId the attachment id; null or blank generates one
   * @return the attachment
   */
  public Mono<SupervisorAttachmentInfo> attach(
      final String supervisorId,
      final String scope,
      final Boolean enabled,
      final String attachmentId) {
    return Mono.fromSupplier(
            () ->
                new SupervisorAttachment(
                    attachmentId == null || attachmentId.isBlank()
                        ? UUID.randomUUID().toString()
                        : attachmentId.strip(),
                    supervisorId,
                    SupervisorScope.parse(scope),
                    enabled == null || enabled))
        .flatMap(
            attachment ->
                manual(attachment.id()).then(Mono.defer(() -> service.attach(attachment))))
        .map(SupervisorProvider::info);
  }

  /**
   * Detaches a manual attachment.
   *
   * @param attachmentId the attachment id
   * @return the result; an error for an unknown attachment or one that comes from a definition
   */
  public Mono<SupervisorDetached> detach(final String attachmentId) {
    return manual(attachmentId)
        .then(Mono.defer(() -> service.detach(attachmentId)))
        .thenReturn(new SupervisorDetached(attachmentId, true));
  }

  /** Errors with a conflict if the attachment exists and was not made by hand. */
  private Mono<Void> manual(final String attachmentId) {
    return service
        .attachments()
        .filter(a -> a.id().equals(attachmentId) && a.source() != SupervisorSource.MANUAL)
        .next()
        .flatMap(
            a ->
                Mono.<Void>error(
                    new ConflictException(
                        "Attachment "
                            + attachmentId
                            + " comes from a "
                            + a.source()
                            + " source; edit the definition that declares it")));
  }

  private SupervisorDescriptor descriptor(final String type) {
    return service
        .describe(type)
        .orElseThrow(() -> new IllegalArgumentException("Unknown supervisor type: " + type));
  }

  private static SupervisorAttachmentInfo info(final SupervisorAttachment attachment) {
    return new SupervisorAttachmentInfo(
        attachment.id(),
        attachment.supervisorId(),
        attachment.scope().key(),
        attachment.enabled(),
        attachment.source().name());
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
    return service
        .replay(id, settings, executionId)
        .map(
            found ->
                new SupervisorReplay(
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
                        .toList()));
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
              return new SupervisorCredential(
                  id,
                  issued.token(),
                  issued.expiresAt() == null ? null : issued.expiresAt().toString());
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
    return ownership
        .of(sessionId, workflowId)
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
