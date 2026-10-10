// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.service.archive.ArchivedExecution;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.ExecutionRejectedException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.gateway.RestartCompletionSink;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.LinguisticNaming",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports"
})
class RestartFromNodeCommandProcessorTest {

  private static final String EXEC = "exec-1";

  private final ExecutionControlRegistry registry = mock(ExecutionControlRegistry.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);
  private final RestartCompletionSink completionSink = mock(RestartCompletionSink.class);
  private final ExecutionArchive archive = new ExecutionArchive();
  private final InMemoryNodeCheckpointStore checkpoints = new InMemoryNodeCheckpointStore(3);

  private RestartFromNodeCommandProcessor processor;

  private static WorkflowNode node(final String nodeId) {
    return new WorkflowNode(nodeId, "type", Map.of());
  }

  /** Builds the graph used by the tests: a chain plus an independent branch. */
  private static PreparedWorkflow graph() {
    final WorkflowNode trigger = node("trigger");
    final WorkflowNode mid = node("mid");
    final WorkflowNode sink = node("sink");
    final WorkflowNode trigger2 = node("trigger2");
    final WorkflowNode side = node("side");
    return new PreparedWorkflow(
        List.of(),
        Map.of(
            "trigger", List.of(mid),
            "mid", List.of(sink),
            "sink", List.of(),
            "trigger2", List.of(side),
            "side", List.of()),
        Map.of(
            "trigger", List.of(),
            "mid", List.of(trigger),
            "sink", List.of(mid),
            "trigger2", List.of(),
            "side", List.of(trigger2)),
        Map.of(),
        List.of(trigger, mid, sink, trigger2, side),
        mock(WorkflowTemplate.class));
  }

  private Message<?> message(final String payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @BeforeEach
  void setUp() {
    processor =
        new RestartFromNodeCommandProcessor(
            registry, archive, orchestrator, checkpoints, scheduler, completionSink);
    archive.put(new ArchivedExecution(EXEC, "s1", "w1", graph(), "v1", Map.of()));
    when(orchestrator.restartFromNode(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Mono.never());
  }

  @Test
  void canProcess_restartFromNodeCommand_returnsTrue() {
    final ExecutionControlCommand command = new RestartFromNodeCommand(EXEC, "mid", "new-1");

    assertThat(processor.canProcess(command)).isTrue();
  }

  @Test
  void canProcess_otherCommand_returnsFalse() {
    assertThat(processor.canProcess(new PauseWorkflowCommand(EXEC))).isFalse();
  }

  @Test
  void finishedExecution_replaysAllRecordedMessagesOfTheBoundaryNodeInOrder() {
    final Message<?> first = message("a");
    final Message<?> second = message("b");
    checkpoints.save(EXEC, "trigger", first).block();
    checkpoints.save(EXEC, "trigger", second).block();
    checkpoints.save(EXEC, "trigger2", message("unrelated")).block();
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.empty());

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyComplete();

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, List<Message<?>>>> outputs =
        ArgumentCaptor.forClass(Map.class);
    verify(orchestrator)
        .restartFromNode(
            eq("s1"), eq("w1"), eq(EXEC), eq("new-1"), any(), eq("mid"), outputs.capture());
    // only the node feeding the re-run part is replayed; the independent branch is left alone
    assertThat(outputs.getValue()).containsOnlyKeys("trigger");
    assertThat(outputs.getValue().get("trigger")).containsExactly(first, second);
    verify(registry, never()).unregister(any());
    verify(completionSink).completeRestartSuccess("new-1");
  }

  @Test
  void restartingTheTrigger_needsNoRecordedInput() {
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.empty());

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "trigger", "new-1")))
        .verifyComplete();

    verify(completionSink).completeRestartSuccess("new-1");
  }

  @Test
  void finishedExecution_isRefusedWhenTheLimitsAreFull() {
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new ExecutionRejectedException("Execution limit reached"))
        .when(scheduler)
        .assertCanAccept("s1", "w1");

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyComplete();

    verify(completionSink)
        .completeRestartFailure(eq("new-1"), any(ExecutionRejectedException.class));
    verify(orchestrator, never()).restartFromNode(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void runningExecution_isStoppedAndDetachedBeforeTheRestart() {
    final ExecutionControl control = mock(ExecutionControl.class);
    final Sinks.One<Void> safeStop = Sinks.one();
    when(control.executionId()).thenReturn(EXEC);
    when(control.safeStopSink()).thenReturn(safeStop);
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.of(control));

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyComplete();

    assertThat(safeStop.asMono().toFuture()).isDone();
    verify(registry).unregister(EXEC);
  }

  @Test
  void incompleteRecordedOutput_isRefusedInsteadOfReplayedPartially() {
    for (int i = 0; i < 4; i++) {
      checkpoints.save(EXEC, "trigger", message("m" + i)).block();
    }

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyComplete();

    verify(completionSink)
        .completeRestartFailure(
            eq("new-1"),
            org.mockito.ArgumentMatchers.argThat(
                e ->
                    e instanceof IllegalArgumentException
                        && e.getMessage().contains("'trigger'")
                        && e.getMessage().contains("incomplete")));
    verify(orchestrator, never()).restartFromNode(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void unknownNode_andUnknownExecution_reportFailures() {
    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "ghost", "new-1")))
        .verifyComplete();
    StepVerifier.create(processor.process(new RestartFromNodeCommand("gone", "mid", "new-2")))
        .verifyComplete();

    verify(completionSink).completeRestartFailure(eq("new-1"), any(IllegalArgumentException.class));
    verify(completionSink).completeRestartFailure(eq("new-2"), any(IllegalArgumentException.class));
    verify(orchestrator, never()).restartFromNode(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void orchestratorFailingLater_isNotReportedThroughTheCompletionSink() {
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.empty());
    when(orchestrator.restartFromNode(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Mono.error(new RuntimeException("boom")));

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "trigger", "new-1")))
        .verifyComplete();

    verify(completionSink).completeRestartSuccess("new-1");
    verify(completionSink, never()).completeRestartFailure(any(), any());
  }

  @Test
  void getPriority_returnsCorrectValue() {
    assertThat(processor.getPriority()).isEqualTo(20);
  }
}
