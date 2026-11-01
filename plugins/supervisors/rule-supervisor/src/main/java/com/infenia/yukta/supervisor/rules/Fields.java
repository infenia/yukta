// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.InputRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * The fields of an event or command that a rule's {@code if} condition can read. Every field of the
 * kind is present, with {@code null} when it does not apply, so a condition never fails on a
 * missing field.
 */
@UtilityClass
@SuppressWarnings("PMD.UseConcurrentHashMap")
public class Fields {

  /** Name of the node field. */
  public static final String NODE_ID = "nodeId";

  /** Name of the status field. */
  public static final String STATUS = "status";

  /** Name of the attempt field (restarts that led to the run). */
  public static final String ATTEMPT = "attempt";

  /** Name of the module field. */
  public static final String MODULE = "module";

  /** Name of the workflow field. */
  public static final String WORKFLOW_ID = "workflowId";

  /** Field: the id of an input request. */
  public static final String REQUEST_ID = "requestId";

  /** Field: why a command was refused or an input request ended. */
  public static final String REASON_FIELD = "reason";

  /**
   * Fields of an event.
   *
   * @param event the event
   * @return the fields
   */
  public static Map<String, Object> eventFields(final ExecutionEvent event) {
    final Map<String, Object> fields = new HashMap<>();
    fields.put("executionId", event.executionId());
    fields.put(NODE_ID, null);
    fields.put(STATUS, null);
    fields.put(MODULE, null);
    fields.put(WORKFLOW_ID, null);
    switch (event) {
      case ExecutionEvent.NodeStatusChanged node -> {
        fields.put(WORKFLOW_ID, node.workflowId());
        fields.put(NODE_ID, node.nodeId());
        fields.put(MODULE, node.module());
        fields.put(STATUS, node.status());
        fields.put("metadata", node.metadata());
      }
      case ExecutionEvent.WorkflowStatusChanged workflow -> {
        fields.put(WORKFLOW_ID, workflow.workflowId());
        fields.put(STATUS, workflow.status());
      }
      case ExecutionEvent.ControlOutcome outcome -> {
        fields.put("command", outcome.command());
        fields.put("actor", outcome.actor());
        fields.put("applied", outcome.applied());
        fields.put(REASON_FIELD, outcome.reason());
      }
      case ExecutionEvent.LogLine line -> {
        fields.put(NODE_ID, line.nodeId());
        fields.put("line", line.line());
        fields.put("level", line.level().name());
      }
      case ExecutionEvent.EventsMissed missed -> {
        fields.put(STATUS, "EVENTS_MISSED");
        fields.put("missed", missed.count());
      }
      case ExecutionEvent.InputRequested requested ->
          inputFields(fields, requested.request(), "INPUT_REQUESTED");
      case ExecutionEvent.InputEscalated escalated -> {
        inputFields(fields, escalated.request(), "INPUT_ESCALATED");
        fields.put(REASON_FIELD, escalated.reason());
      }
      case ExecutionEvent.InputAnswered answered -> {
        fields.put(WORKFLOW_ID, answered.workflowId());
        fields.put(STATUS, "INPUT_ANSWERED");
        fields.put(REQUEST_ID, answered.requestId());
        fields.put("decision", answered.decision());
        fields.put("responder", answered.responder().id());
        fields.put("responderType", answered.responder().type().name());
      }
      case ExecutionEvent.InputExpired expired -> {
        fields.put(WORKFLOW_ID, expired.workflowId());
        fields.put(STATUS, "INPUT_EXPIRED");
        fields.put(REQUEST_ID, expired.requestId());
      }
      case ExecutionEvent.InputCancelled cancelled -> {
        fields.put(WORKFLOW_ID, cancelled.workflowId());
        fields.put(STATUS, "INPUT_CANCELLED");
        fields.put(REQUEST_ID, cancelled.requestId());
        fields.put(REASON_FIELD, cancelled.reason());
      }
    }
    return fields;
  }

  /** The fields of an event that carries a request: what rules match on to answer or escalate. */
  private static void inputFields(
      final Map<String, Object> fields, final InputRequest request, final String status) {
    fields.put(WORKFLOW_ID, request.workflowId());
    fields.put(NODE_ID, request.nodeId());
    fields.put(STATUS, status);
    fields.put(REQUEST_ID, request.id());
    fields.put("kind", request.spec().kind().name());
    fields.put("risk", request.spec().risk().name());
    fields.put("state", request.state().name());
    fields.put("tier", request.tier());
    fields.put("requester", request.spec().requester());
  }

  /**
   * Fields of a command waiting for review.
   *
   * @param request the command and who sent it
   * @return the fields
   */
  public static Map<String, Object> commandFields(final ControlRequest request) {
    final ExecutionControlCommand command = request.command();
    final Map<String, Object> fields = new HashMap<>();
    fields.put("executionId", command.executionId());
    fields.put("actor", request.actor());
    fields.put(NODE_ID, nodeId(command));
    fields.put(
        "mode",
        command instanceof ExecutionControlCommand.StopWorkflowCommand stop
            ? String.valueOf(stop.mode())
            : null);
    fields.put(REASON_FIELD, reason(command));
    fields.put(
        "immediate",
        command instanceof ExecutionControlCommand.StopNodeCommand stop ? stop.immediate() : null);
    return fields;
  }

  private static String nodeId(final ExecutionControlCommand command) {
    return switch (command) {
      case ExecutionControlCommand.PauseNodeCommand pause -> pause.nodeId();
      case ExecutionControlCommand.ResumeNodeCommand resume -> resume.nodeId();
      case ExecutionControlCommand.StopNodeCommand stop -> stop.nodeId();
      case ExecutionControlCommand.SkipNodeCommand skip -> skip.nodeId();
      case ExecutionControlCommand.EnableStepModeCommand enable -> enable.nodeId();
      case ExecutionControlCommand.DisableStepModeCommand disable -> disable.nodeId();
      case ExecutionControlCommand.StepNodeCommand step -> step.nodeId();
      case ExecutionControlCommand.RestartFromNodeCommand restart -> restart.fromNodeId();
      case ExecutionControlCommand.SetBreakpointCommand breakpoint -> breakpoint.nodeId();
      case ExecutionControlCommand.SignalNodeCommand signal -> signal.nodeId();
      default -> null;
    };
  }

  private static String reason(final ExecutionControlCommand command) {
    return switch (command) {
      case ExecutionControlCommand.StopNodeCommand stop -> stop.reason();
      case ExecutionControlCommand.StopWorkflowCommand stop -> stop.reason();
      default -> null;
    };
  }
}
