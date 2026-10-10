// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyStaticImports"})
class NodeStreamsTest {

  private static final String EXEC = "exec-1";
  private static final String NODE_ID = "node-1";
  private static final String TASK = "default";

  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final WorkflowNode node = new WorkflowNode(NODE_ID, "type", Map.of());

  private Flux<Message<?>> decorate(final Flux<Message<?>> source) {
    return NodeStreams.withTimeoutAndTracking(node, Duration.ofMillis(200), tracker, EXEC, source);
  }

  private void expectStatus(final TaskStatus status, final int times) {
    verify(tracker, times(times))
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(status), anyMap());
  }

  @Test
  void success_reportsRunningThenSuccess_andPassesMessagesThrough() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");

    StepVerifier.create(decorate(Flux.just(msg))).expectNext(msg).verifyComplete();

    final InOrder order = inOrder(tracker);
    order
        .verify(tracker)
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.RUNNING), anyMap());
    order
        .verify(tracker)
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.SUCCESS), anyMap());
    expectStatus(TaskStatus.FAILURE, 0);
  }

  @Test
  void error_reportsFailureExactlyOnce() {
    StepVerifier.create(decorate(Flux.error(new IllegalStateException("boom"))))
        .expectError(IllegalStateException.class)
        .verify();

    expectStatus(TaskStatus.RUNNING, 1);
    expectStatus(TaskStatus.FAILURE, 1);
    expectStatus(TaskStatus.SUCCESS, 0);
  }

  @Test
  void stalledStream_timesOutAndReportsFailure() {
    StepVerifier.create(decorate(Flux.never())).expectError(TimeoutException.class).verify();

    expectStatus(TaskStatus.FAILURE, 1);
  }

  @Test
  void nothingIsReportedUntilSubscribed() {
    decorate(Flux.empty());

    verify(tracker, never())
        .emitTaskStatusEvent(eq(EXEC), eq(NODE_ID), eq(TASK), eq(TaskStatus.RUNNING), anyMap());
  }
}
