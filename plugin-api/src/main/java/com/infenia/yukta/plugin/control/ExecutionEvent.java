// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.Responder;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Something that happened to a running workflow, as seen by an {@link ExecutionSupervisor}.
 *
 * <p>The feed is best effort: a supervisor that cannot keep up misses events. It is told with an
 * {@link EventsMissed} before the next event it receives, and a rule that must not miss a case
 * reads the durable execution log from that point.
 */
public sealed interface ExecutionEvent {

  /**
   * The execution the event belongs to.
   *
   * @return the execution id
   */
  String executionId();

  /**
   * The session that owns the execution.
   *
   * @return the session id; empty for {@link EventsMissed}, {@code null} when the run is not known
   */
  @Nullable String sessionId();

  /**
   * The workflow the execution runs.
   *
   * @return the workflow id; empty for {@link EventsMissed}, {@code null} when the run is not known
   */
  @Nullable String workflowId();

  /**
   * When the event happened.
   *
   * @return the time
   */
  Instant timestamp();

  /**
   * A node changed status.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param nodeId the node
   * @param module the node's plugin type
   * @param status the new status, a {@code TaskStatus} name
   * @param metadata what the node reported
   * @param timestamp when it happened
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the compact constructor stores an unmodifiable copy")
  record NodeStatusChanged(
      String executionId,
      String sessionId,
      String workflowId,
      String nodeId,
      String module,
      String status,
      Map<String, Object> metadata,
      Instant timestamp)
      implements ExecutionEvent {

    /** Keeps an immutable copy of the metadata. */
    public NodeStatusChanged {
      // Map.copyOf rejects null values, and node output can hold them
      metadata =
          metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
  }

  /**
   * A workflow changed status.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it runs
   * @param status the new status, a {@code TaskStatus} name
   * @param timestamp when it happened
   */
  record WorkflowStatusChanged(
      String executionId, String sessionId, String workflowId, String status, Instant timestamp)
      implements ExecutionEvent {}

  /**
   * A control command was applied or rejected.
   *
   * @param executionId the execution the command addressed
   * @param sessionId the session that owns it, or {@code null} when the run is not known
   * @param workflowId the workflow it runs, or {@code null} likewise
   * @param command the command name, for example {@code PauseWorkflowCommand}
   * @param actor who sent it, or {@code null} when it came through the API
   * @param applied true if it was applied, false if it was rejected
   * @param reason why it was rejected, otherwise {@code null}
   * @param timestamp when it happened
   */
  record ControlOutcome(
      String executionId,
      @Nullable String sessionId,
      @Nullable String workflowId,
      String command,
      @Nullable String actor,
      boolean applied,
      @Nullable String reason,
      Instant timestamp)
      implements ExecutionEvent {}

  /**
   * A node wrote a log line.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it, or {@code null} when the run is not known
   * @param workflowId the workflow it runs, or {@code null} likewise
   * @param nodeId the node
   * @param line the line, a single line of text
   * @param level its level, as the plugin reported it or the engine rated it
   * @param timestamp when it happened
   */
  record LogLine(
      String executionId,
      @Nullable String sessionId,
      @Nullable String workflowId,
      String nodeId,
      String line,
      LogLevel level,
      Instant timestamp)
      implements ExecutionEvent {

    /** Rejects a missing level, so a line is never shown with a level nobody gave it. */
    public LogLine {
      Objects.requireNonNull(level, "level");
    }
  }

  /**
   * Events were dropped for this supervisor because it fell behind; it may have missed a status
   * change or a log line. Injected by the host, never published to the feed.
   *
   * @param count how many events were dropped since the last event it received
   * @param timestamp when the gap was noticed
   */
  record EventsMissed(long count, Instant timestamp) implements ExecutionEvent {

    /**
     * A gap belongs to no single execution.
     *
     * @return an empty id
     */
    @Override
    public String executionId() {
      return "";
    }

    /**
     * A gap belongs to no session.
     *
     * @return an empty id
     */
    @Override
    public String sessionId() {
      return "";
    }

    /**
     * A gap belongs to no workflow.
     *
     * @return an empty id
     */
    @Override
    public String workflowId() {
      return "";
    }
  }

  /**
   * A plugin of a run asked for input. The request is the redacted snapshot. Supervisors that may
   * answer it see this event and answer with an {@code AnswerInputCommand}.
   *
   * @param request the request as it is now
   * @param timestamp when it was asked
   */
  record InputRequested(InputRequest request, Instant timestamp) implements ExecutionEvent {

    /**
     * Checks the parts.
     *
     * @param request the request
     * @param timestamp when it was asked
     */
    public InputRequested {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public String executionId() {
      return request.executionId();
    }

    @Override
    public String sessionId() {
      return request.sessionId();
    }

    @Override
    public String workflowId() {
      return request.workflowId();
    }
  }

  /**
   * An input request was handed up to the next tier.
   *
   * @param request the request as it is now, with its new tier and targets
   * @param reason why it was handed up, such as {@code tier timeout} or the sender's reason
   * @param timestamp when it moved
   */
  record InputEscalated(InputRequest request, String reason, Instant timestamp)
      implements ExecutionEvent {

    /**
     * Checks the parts.
     *
     * @param request the request
     * @param reason why it was handed up
     * @param timestamp when it moved
     */
    public InputEscalated {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(timestamp, "timestamp");
    }

    @Override
    public String executionId() {
      return request.executionId();
    }

    @Override
    public String sessionId() {
      return request.sessionId();
    }

    @Override
    public String workflowId() {
      return request.workflowId();
    }
  }

  /**
   * An input request was answered. The feed reaches every supervisor in scope and the event stream,
   * so the answer's text and data are not in it, only who decided what.
   *
   * @param executionId the run that asked
   * @param sessionId the session of the run
   * @param workflowId the workflow of the run
   * @param requestId the request
   * @param responder who answered
   * @param decision what was decided
   * @param timestamp when it was answered
   */
  record InputAnswered(
      String executionId,
      @Nullable String sessionId,
      @Nullable String workflowId,
      String requestId,
      Responder responder,
      String decision,
      Instant timestamp)
      implements ExecutionEvent {}

  /**
   * An input request expired without an answer.
   *
   * @param executionId the run that asked
   * @param sessionId the session of the run
   * @param workflowId the workflow of the run
   * @param requestId the request
   * @param timestamp when it expired
   */
  record InputExpired(
      String executionId,
      @Nullable String sessionId,
      @Nullable String workflowId,
      String requestId,
      Instant timestamp)
      implements ExecutionEvent {}

  /**
   * An input request was cancelled, for example because its run ended.
   *
   * @param executionId the run that asked
   * @param sessionId the session of the run
   * @param workflowId the workflow of the run
   * @param requestId the request
   * @param reason why, such as {@code run-ended} or {@code node-cancelled}
   * @param timestamp when it was cancelled
   */
  record InputCancelled(
      String executionId,
      @Nullable String sessionId,
      @Nullable String workflowId,
      String requestId,
      String reason,
      Instant timestamp)
      implements ExecutionEvent {}
}
