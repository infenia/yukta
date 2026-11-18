// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.assembly.ExecutionContextBuilder;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.redact.SecretRedactor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records what a node started with, in the metadata of its task under {@value #INPUT_KEY}: the
 * payload of its first input message, or for a trigger the payload the run was started with. It is
 * redacted and cut to the live-log preview size, because a progress snapshot goes to every client.
 */
@UtilityClass
public class NodeInputs {

  /** The task metadata key that holds the text of the input. */
  public static final String INPUT_KEY = "input";

  private static final SecretRedactor REDACTOR = new SecretRedactor(null, List.of());

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Reports the payload of the first message that reaches a node.
   *
   * @param input the node's input, after its gate
   * @param tracker receives the status event that carries the input
   * @param executionId the run
   * @param node the node
   * @return the same stream
   */
  public static Flux<Message<?>> recordFirst(
      final Flux<Message<?>> input,
      final TaskTrackerService tracker,
      final String executionId,
      final WorkflowNode node) {
    // ponytail: the first message only; a node with many inputs shows what it began with
    final AtomicBoolean seen = new AtomicBoolean();
    return input.doOnNext(message -> {
      if (seen.compareAndSet(false, true)) {
        report(tracker, executionId, node, message.payload());
      }
    });
  }

  /**
   * Reports the payload the run was started with, for a trigger, which has no input message.
   *
   * @param stream the trigger's output; the payload is read from the subscriber's context
   * @param tracker receives the status event that carries the input
   * @param executionId the run
   * @param node the node
   * @return the same stream
   */
  public static Flux<Message<?>> recordStartPayload(
      final Flux<Message<?>> stream,
      final TaskTrackerService tracker,
      final String executionId,
      final WorkflowNode node) {
    return Flux.deferContextual(context -> {
      final Object payload = context.getOrDefault(ExecutionContextBuilder.CTX_PAYLOAD, null);
      if (payload instanceof final Map<?, ?> values && !values.isEmpty()) {
        report(tracker, executionId, node, payload);
      }
      return stream;
    });
  }

  /**
   * The text of an input: JSON for a map or list, else its text; secrets replaced, cut when long.
   *
   * @param payload the payload
   * @return the text
   */
  /* default */ static String describe(final Object payload) {
    final Object clean = payload instanceof String ? payload : redact(payload);
    String text;
    try {
      text =
          clean instanceof Map<?, ?> || clean instanceof Collection<?>
              ? JSON.writeValueAsString(clean)
              : String.valueOf(clean);
    } catch (JacksonException _) {
      text = String.valueOf(clean);
    }
    return LivePayloadPreview.preview(REDACTOR.redactText(text));
  }

  private static Object redact(final Object payload) {
    return payload == null ? "null" : REDACTOR.redact(payload);
  }

  // The status RUNNING never moves a node back, and its metadata is merged into the task.
  private static void report(
      final TaskTrackerService tracker,
      final String executionId,
      final WorkflowNode node,
      final Object payload) {
    tracker.emitTaskStatusEvent(
        executionId,
        node.nodeId(),
        node.type(),
        TaskStatus.RUNNING,
        Map.of(INPUT_KEY, describe(payload)));
  }
}
