// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.assembly.ExecutionContextBuilder;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
class NodeInputsTest {

  private static final String EXEC = "exec-1";
  private static final String NODE_ID = "branch";
  private static final String NODE_TYPE = "BRANCH";

  private final TaskTrackerService tracker = Mockito.mock(TaskTrackerService.class);
  private final WorkflowNode node = new WorkflowNode(NODE_ID, NODE_TYPE, Map.of());

  private static Message<?> msg(final Object payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @Test
  void firstInputIsReportedOnceAsRedactedJson() {
    final Flux<Message<?>> input =
        Flux.<Message<?>>just(
            msg(Map.of("user", "ada", "token", "abc123")), msg(Map.of("user", "bob")));

    StepVerifier.create(NodeInputs.recordFirst(input, tracker, EXEC, node))
        .expectNextCount(2)
        .verifyComplete();

    Mockito.verify(tracker, Mockito.times(1))
        .emitTaskStatusEvent(
            eq(EXEC),
            eq(NODE_ID),
            eq(NODE_TYPE),
            eq(TaskStatus.RUNNING),
            argThat((Map<String, Object> metadata) ->
                metadata.get(NodeInputs.INPUT_KEY) instanceof final String text
                    && text.contains("\"user\":\"ada\"")
                    && text.contains("[REDACTED]")
                    && !text.contains("abc123")));
  }

  @Test
  void triggerReportsTheStartPayloadFromTheContext() {
    final Flux<Message<?>> stream = Flux.<Message<?>>just(msg("out"));

    StepVerifier.create(
            NodeInputs.recordStartPayload(stream, tracker, EXEC, node)
                .contextWrite(context ->
                    context.put(ExecutionContextBuilder.CTX_PAYLOAD, Map.of("branch", "main"))))
        .expectNextCount(1)
        .verifyComplete();

    Mockito.verify(tracker)
        .emitTaskStatusEvent(
            eq(EXEC),
            eq(NODE_ID),
            eq(NODE_TYPE),
            eq(TaskStatus.RUNNING),
            argThat((Map<String, Object> metadata) ->
                "{\"branch\":\"main\"}".equals(metadata.get(NodeInputs.INPUT_KEY))));
  }

  @Test
  void triggerWithoutStartPayloadReportsNothing() {
    StepVerifier.create(NodeInputs.recordStartPayload(Flux.just(msg("out")), tracker, EXEC, node))
        .expectNextCount(1)
        .verifyComplete();

    Mockito.verify(tracker, Mockito.never())
        .emitTaskStatusEvent(
            eq(EXEC), eq(NODE_ID), eq(NODE_TYPE), eq(TaskStatus.RUNNING), argThat(m -> true));
  }

  @Test
  void textListsAndLongInputsAreBounded() {
    assertThat(NodeInputs.describe("plain")).isEqualTo("plain");
    assertThat(NodeInputs.describe(List.of(1, 2))).isEqualTo("[1,2]");
    assertThat(NodeInputs.describe(null)).isEqualTo("null");
    assertThat(NodeInputs.describe("x".repeat(5000))).contains("truncated");
  }

  @Test
  void valueThatCannotBeSerializedFallsBackToItsText() {
    assertThat(NodeInputs.describe(Map.of("broken", new Unserializable()))).contains("broken=");
  }

  /** A bean whose getter fails, so JSON serialization throws; Jackson needs the public getter. */
  @SuppressWarnings("PMD.PublicMemberInNonPublicType")
  public static final class Unserializable {
    public String getValue() {
      throw new IllegalStateException("cannot read");
    }
  }
}
