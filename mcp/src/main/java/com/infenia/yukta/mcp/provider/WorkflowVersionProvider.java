// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.WorkflowRollback;
import com.infenia.yukta.mcp.dto.WorkflowVersions;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.service.workflow.store.WorkflowDiff;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Saved versions of workflow definitions for the MCP tools. */
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.OnlyOneReturn")
public class WorkflowVersionProvider {

  /** Keeps the saved versions. */
  private final WorkflowDefinitionStore definitions;

  /** Prepares the workflow again after a rollback. */
  private final ControlBusGateway controlBus;

  /**
   * List the saved versions of a workflow.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return the versions, newest first
   */
  public Mono<WorkflowVersions> versions(final String sessionId, final String workflowId) {
    return definitions
        .revisions(sessionId, workflowId)
        .collectList()
        .map(list -> new WorkflowVersions(sessionId, workflowId, list));
  }

  /**
   * Compare two versions, or a version with the current definition.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param olderVersion the older version
   * @param newerVersion the newer version, or null for the current definition
   * @return the nodes and edges added, removed or changed
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public Mono<WorkflowDiff> diff(
      final String sessionId,
      final String workflowId,
      final String olderVersion,
      final String newerVersion) {
    if (olderVersion == null || olderVersion.isBlank()) {
      return Mono.error(new IllegalArgumentException("olderVersion is required."));
    }
    final Mono<WorkflowDefinition> newer =
        newerVersion == null || newerVersion.isBlank()
            ? definitions.find(sessionId, workflowId)
            : definitions.findRevision(sessionId, workflowId, newerVersion);
    return Mono.zip(definitions.findRevision(sessionId, workflowId, olderVersion), newer)
        .switchIfEmpty(
            Mono.error(
                () -> new NotFoundException("Version of workflow " + workflowId + " not found")))
        .map(pair -> WorkflowDiff.between(pair.getT1(), pair.getT2()));
  }

  /**
   * Make an earlier version the current one; it becomes a new save, so it can be undone the same
   * way. Runs that already started keep the version they use.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param version the version to restore
   * @return the workflow and version
   */
  public Mono<WorkflowRollback> rollback(
      final String sessionId, final String workflowId, final String version) {
    if (version == null || version.isBlank()) {
      return Mono.error(new IllegalArgumentException("version is required."));
    }
    return definitions
        .findRevision(sessionId, workflowId, version)
        .switchIfEmpty(
            Mono.error(
                () ->
                    new NotFoundException(
                        "Version " + version + " of workflow " + workflowId + " not found")))
        .flatMap(
            definition ->
                definitions
                    .save(sessionId, definition)
                    .then(controlBus.compileAndCacheWorkflow(sessionId, definition))
                    .thenReturn(new WorkflowRollback(workflowId, version)));
  }
}
