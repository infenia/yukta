// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * One entry of the append-only execution log.
 *
 * @param type what happened
 * @param executionId the execution
 * @param sessionId the session that owns it
 * @param workflowId the workflow it runs
 * @param definitionVersion content hash of the graph (set on {@code RUN_STARTED})
 * @param status the final status (set on {@code RUN_FINISHED} and {@code RUN_ABORTED})
 * @param nodeStatuses final status of each node (set on {@code RUN_FINISHED})
 * @param nodeId the node (set on {@code NODE_FINISHED})
 * @param payload the trigger payload the run started with (set on {@code RUN_STARTED})
 * @param timestamp when it happened
 * @param initiator who or what started the run (set on {@code RUN_STARTED}), or null if unknown
 */
@SuppressWarnings({"PMD.UseObjectForClearerAPI", "PMD.NullAssignment"})
public record ExecutionLogEvent(
    Type type,
    String executionId,
    String sessionId,
    String workflowId,
    @Nullable String definitionVersion,
    @Nullable String status,
    Map<String, String> nodeStatuses,
    @Nullable String nodeId,
    @Nullable Map<String, Object> payload,
    Instant timestamp,
    @Nullable String initiator) {

  /** The reserved payload key under which a start carries who or what started the run. */
  public static final String INITIATOR_KEY = "yukta.initiator";

  /** Run id under which changes that belong to no run (scheduler limits) are recorded. */
  public static final String SCHEDULER = "scheduler";

  /** Payload keys of a control event: the command name, who sent it and why it was rejected. */
  public static final String COMMAND_KEY = "command";

  /** See {@link #COMMAND_KEY}. */
  public static final String ACTOR_KEY = "actor";

  /** See {@link #COMMAND_KEY}. */
  public static final String REASON_KEY = "reason";

  /**
   * Keeps an immutable copy of the node statuses.
   *
   * @param type what happened
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param definitionVersion content hash of the graph
   * @param status the final status
   * @param nodeStatuses final status of each node
   * @param nodeId the node, for node events
   * @param payload the trigger payload, for run starts
   * @param timestamp when it happened
   * @param initiator who or what started the run, or null if unknown
   */
  public ExecutionLogEvent {
    nodeStatuses = nodeStatuses == null ? Map.of() : Map.copyOf(nodeStatuses);
    payload = payload == null ? null : Collections.unmodifiableMap(new HashMap<>(payload));
  }

  /** Kind of log entry. */
  public enum Type {
    RUN_STARTED,
    RUN_FINISHED,
    RUN_ABORTED,
    CONTROL_APPLIED,
    CONTROL_REJECTED,
    /** A node ended successfully, so its recorded output is a safe place to resume from. */
    NODE_FINISHED,
    /** An aborted run was resumed; its status holds the id of the new run. */
    RUN_RESUMED,
    /** A start with an idempotency key succeeded; its status holds the scope and the key. */
    IDEMPOTENCY_KEY
  }

  /**
   * A run has started.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param definitionVersion content hash of the graph
   * @param payload the trigger payload, kept so an aborted run can be resumed
   * @return the event
   */
  public static ExecutionLogEvent started(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String definitionVersion,
      @Nullable final Map<String, Object> payload) {
    return new ExecutionLogEvent(
        Type.RUN_STARTED,
        executionId,
        sessionId,
        workflowId,
        definitionVersion,
        null,
        Map.of(),
        null,
        payload,
        Instant.now(),
        initiatorOf(payload));
  }

  /**
   * A run has started whose payload is not kept, so it cannot be resumed.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param definitionVersion content hash of the graph
   * @return the event
   */
  public static ExecutionLogEvent started(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String definitionVersion) {
    return started(executionId, sessionId, workflowId, definitionVersion, null);
  }

  /**
   * The payload with the initiator under its reserved key. A caller's own value of that key is
   * dropped, so a run's initiator cannot be forged through the payload. The parent-run key is
   * dropped too: only a sub-workflow node may name a parent, and it does not pass through here, so
   * an external caller cannot attach a run to another run's tree.
   *
   * @param payload the trigger payload
   * @param initiator who or what starts the run, or null if not known
   * @return a copy of the payload
   */
  public static Map<String, Object> withInitiator(
      final Map<String, Object> payload, @Nullable final String initiator) {
    final var copy = new HashMap<>(payload);
    copy.remove(INITIATOR_KEY);
    copy.remove("yukta.parentExecutionId"); // SubWorkflowGateway.PARENT_KEY, not visible from here
    if (initiator != null && !initiator.isBlank()) {
      copy.put(INITIATOR_KEY, initiator);
    }
    return copy;
  }

  /** The initiator a start's payload carries under {@link #INITIATOR_KEY}, or null. */
  public static @Nullable String initiatorOf(@Nullable final Map<String, Object> payload) {
    return payload != null && payload.get(INITIATOR_KEY) instanceof String text && !text.isBlank()
        ? text
        : null;
  }

  /**
   * A node ended successfully.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param nodeId the node
   * @return the event
   */
  public static ExecutionLogEvent nodeFinished(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String nodeId) {
    return new ExecutionLogEvent(
        Type.NODE_FINISHED,
        executionId,
        sessionId,
        workflowId,
        null,
        "SUCCESS",
        Map.of(),
        nodeId,
        null,
        Instant.now(),
        null);
  }

  /**
   * A start with an idempotency key created an execution.
   *
   * @param executionId the execution it created
   * @param scopedKey the scope and the client's key
   * @return the event
   */
  public static ExecutionLogEvent idempotencyKey(final String executionId, final String scopedKey) {
    return new ExecutionLogEvent(
        Type.IDEMPOTENCY_KEY,
        executionId,
        "",
        "",
        null,
        scopedKey,
        Map.of(),
        null,
        null,
        Instant.now(),
        null);
  }

  /**
   * An aborted run was resumed as a new run.
   *
   * @param aborted the run's start event
   * @param newExecutionId the run that continues it
   * @return the event
   */
  public static ExecutionLogEvent resumed(
      final ExecutionLogEvent aborted, final String newExecutionId) {
    return new ExecutionLogEvent(
        Type.RUN_RESUMED,
        aborted.executionId(),
        aborted.sessionId(),
        aborted.workflowId(),
        null,
        newExecutionId,
        Map.of(),
        null,
        null,
        Instant.now(),
        null);
  }

  /**
   * A run has reached a terminal status.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param status the final status
   * @param nodeStatuses final status of each node
   * @return the event
   */
  public static ExecutionLogEvent finished(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final String status,
      final Map<String, String> nodeStatuses) {
    return new ExecutionLogEvent(
        Type.RUN_FINISHED,
        executionId,
        sessionId,
        workflowId,
        null,
        status,
        nodeStatuses,
        null,
        null,
        Instant.now(),
        null);
  }

  /**
   * A run that never finished was found after a restart of the server.
   *
   * @param started the run's start event
   * @return the event
   */
  public static ExecutionLogEvent aborted(final ExecutionLogEvent started) {
    return aborted(started, Map.of());
  }

  /**
   * A run that never finished was found after a restart of the server.
   *
   * @param started the run's start event
   * @param nodeStatuses the nodes known to have ended before the crash
   * @return the event
   */
  public static ExecutionLogEvent aborted(
      final ExecutionLogEvent started, final Map<String, String> nodeStatuses) {
    return new ExecutionLogEvent(
        Type.RUN_ABORTED,
        started.executionId(),
        started.sessionId(),
        started.workflowId(),
        null,
        "ABORTED",
        nodeStatuses,
        null,
        null,
        Instant.now(),
        null);
  }

  /**
   * Records what happened to a control command sent to a run.
   *
   * @param applied true if the command was applied, false if it was rejected
   * @param executionId the run the command addressed
   * @param command the command name, for example {@code PauseWorkflowCommand}
   * @param reason why it was rejected; ignored when applied
   * @return the event
   */
  public static ExecutionLogEvent control(
      final boolean applied, final String executionId, final String command, final String reason) {
    return new ExecutionLogEvent(
        applied ? Type.CONTROL_APPLIED : Type.CONTROL_REJECTED,
        executionId,
        "",
        "",
        null,
        applied ? command : command + ": " + reason,
        Map.of(),
        null,
        null,
        Instant.now(),
        null);
  }

  /**
   * Like {@link #control(boolean, String, String, String)}, with the command, actor and reason also
   * as fields of the payload, so readers need not take them out of the text.
   *
   * @param applied true if the command was applied, false if it was rejected
   * @param executionId the run the command addressed
   * @param logged the text shown in the status: the command, and who sent it if known
   * @param command the command name alone
   * @param actor who sent it, or null
   * @param reason why it was rejected, or null
   * @return the event
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  public static ExecutionLogEvent control(
      final boolean applied,
      final String executionId,
      final String logged,
      final String command,
      @Nullable final String actor,
      @Nullable final String reason) {
    final ExecutionLogEvent base = control(applied, executionId, logged, reason);
    final Map<String, Object> payload = new ConcurrentHashMap<>();
    payload.put(COMMAND_KEY, command);
    if (actor != null) {
      payload.put(ACTOR_KEY, actor);
    }
    if (!applied && reason != null) {
      payload.put(REASON_KEY, reason);
    }
    return new ExecutionLogEvent(
        base.type,
        base.executionId,
        base.sessionId,
        base.workflowId,
        null,
        base.status,
        Map.of(),
        null,
        payload,
        base.timestamp,
        null);
  }

  /**
   * The same event without the payload, for a log that cannot write the payload.
   *
   * @return the event
   */
  public ExecutionLogEvent withoutPayload() {
    return new ExecutionLogEvent(
        type,
        executionId,
        sessionId,
        workflowId,
        definitionVersion,
        status,
        nodeStatuses,
        nodeId,
        null,
        timestamp,
        null);
  }
}
