// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ReplayNodeAssemblerTest {

  private static final String EXECUTION_ID = "new";
  private static final String NODE_TYPE = "type";
  private static final String PREVIOUS_EXECUTION_ID = "prev";
  private final TaskTrackerService tracker = Mockito.mock(TaskTrackerService.class);
  private final StreamTopologyDecorator decorator = Mockito.mock(StreamTopologyDecorator.class);
  private final ExecutionControl control = Mockito.mock(ExecutionControl.class);
  private final WorkflowNode node = new WorkflowNode("n", NODE_TYPE, Map.of());
  private final ReplayNodeAssembler assembler = new ReplayNodeAssembler(tracker, decorator);

  @SuppressWarnings("unchecked")
  private final Flux<Message<?>>[] streams = new Flux[1];

  private AssemblyContext context;

  @BeforeEach
  void setUp() {
    context =
        new AssemblyContext(
            EXECUTION_ID, "s", "w", Map.of(), control, streams, List.of(), List.of(), List.of());
    Mockito.when(control.applyPostProcessingControls(eq("n"), any()))
        .thenAnswer(inv -> inv.getArgument(1));
    Mockito.when(
            decorator.applyLoggingAndBroadcasting(
                eq(EXECUTION_ID),
                eq("n"),
                any(),
                eq(16),
                any(),
                any(),
                any(),
                eq(NodePolicy.Checkpoint.NONE)))
        .thenAnswer(inv -> inv.getArgument(2));
  }

  @Test
  void replay_emitsRecordedMessagesAndReportsRunningThenSuccess() {
    final Message<?> recorded = Mockito.mock(Message.class);

    assembler.replay(node, 0, 16, List.of(recorded), PREVIOUS_EXECUTION_ID).assemble(context);

    StepVerifier.create(streams[0]).expectNext(recorded).verifyComplete();
    final InOrder order = Mockito.inOrder(tracker);
    order
        .verify(tracker)
        .emitTaskStatusEvent(EXECUTION_ID, "n", NODE_TYPE, TaskStatus.RUNNING, Map.of());
    order
        .verify(tracker)
        .emitTaskStatusEvent(
            EXECUTION_ID,
            "n",
            NODE_TYPE,
            TaskStatus.SUCCESS,
            Map.of("replayedFrom", PREVIOUS_EXECUTION_ID));
  }

  @Test
  void replay_reportsCancelledWhenTheConsumerCancelsBeforeCompletion() {
    assembler
        .replay(node, 0, 16, List.of(Mockito.mock(Message.class)), PREVIOUS_EXECUTION_ID)
        .assemble(context);

    StepVerifier.create(streams[0], 0).thenCancel().verify();

    Mockito.verify(tracker)
        .emitTaskStatusEvent(EXECUTION_ID, "n", NODE_TYPE, TaskStatus.CANCELLED, Map.of());
    Mockito.verify(tracker, Mockito.never())
        .emitTaskStatusEvent(any(), any(), any(), eq(TaskStatus.SUCCESS), any());
  }

  @Test
  void replay_isNotMarkedAsLoggedLiveSoTheOrchestratorLogsIt() {
    final ArgumentCaptor<BooleanSupplier> loggedLive =
        ArgumentCaptor.forClass(BooleanSupplier.class);

    assembler.replay(node, 0, 16, List.of(), PREVIOUS_EXECUTION_ID).assemble(context);

    Mockito.verify(decorator)
        .applyLoggingAndBroadcasting(
            eq(EXECUTION_ID),
            eq("n"),
            any(),
            eq(16),
            any(),
            any(),
            loggedLive.capture(),
            eq(NodePolicy.Checkpoint.NONE));
    assertThat(loggedLive.getValue().getAsBoolean()).isFalse();
  }

  @Test
  void silent_emitsNothingAndReportsNoStatus() {
    assembler.silent(node, 0, 16).assemble(context);

    StepVerifier.create(streams[0]).verifyComplete();
    Mockito.verify(tracker, Mockito.never()).emitTaskStatusEvent(any(), any(), any(), any(), any());
  }
}
