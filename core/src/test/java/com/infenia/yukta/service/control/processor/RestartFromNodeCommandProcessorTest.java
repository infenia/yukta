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
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartFromNodeCommand;
import com.infenia.yukta.service.archive.ArchivedExecution;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.ExecutionRejectedException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.gateway.RestartCompletionSink;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.testsupport.ErrorGuardExtension;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
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
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports"
})
@ExpectedErrorLog(
    "ERROR com.infenia.yukta.service.control.processor.RestartFromNodeCommandProcessor - ")
class RestartFromNodeCommandProcessorTest {

  private static final String EXEC = "exec-1";

  private final ExecutionControlStore registry = mock(ExecutionControlStore.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private ExecutionScheduler scheduler =
      new ExecutionScheduler(0, 0, 100, ExecutionScheduler.OnLimit.QUEUE, Duration.ofSeconds(30));
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
    when(orchestrator.restartFromNode(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Mono.never());
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
            eq("s1"), eq("w1"), eq(EXEC), eq("new-1"), any(), eq("mid"), outputs.capture(), any());
    // only the node feeding the re-run part is replayed; the independent branch is left alone
    assertThat(outputs.getValue()).containsOnlyKeys("trigger");
    assertThat(outputs.getValue().get("trigger")).containsExactly(first, second);
    verify(registry, never()).remove(any());
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
    scheduler = new ExecutionScheduler(1, 0, 0, ExecutionScheduler.OnLimit.REJECT, Duration.ZERO);
    scheduler.acquire("s1", "w1");
    processor =
        new RestartFromNodeCommandProcessor(
            registry, archive, orchestrator, checkpoints, scheduler, completionSink);

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyError(ExecutionRejectedException.class);

    verify(completionSink)
        .completeRestartFailure(eq("new-1"), any(ExecutionRejectedException.class));
    verify(orchestrator, never())
        .restartFromNode(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void runningExecution_isStoppedAndDetachedBeforeTheRestart() {
    final ExecutionControl control = mock(ExecutionControl.class);
    final Sinks.One<Void> safeStop = Sinks.one();
    when(control.executionId()).thenReturn(EXEC);
    when(control.safeStopSink()).thenReturn(safeStop);
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.of(control));
    checkpoints.save(EXEC, "trigger", message("a")).block();
    checkpoints.markComplete(EXEC, "trigger").block();

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyComplete();

    assertThat(safeStop.asMono().toFuture()).isDone();
    verify(registry).remove(EXEC);
  }

  @Test
  void runningExecution_whoseParentHasNotFinished_isNotReplayedPartially() {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.of(control));
    checkpoints.save(EXEC, "trigger", message("still streaming")).block();

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyError(IllegalArgumentException.class);

    verify(completionSink)
        .completeRestartFailure(
            eq("new-1"),
            org.mockito.ArgumentMatchers.argThat(e -> e.getMessage().contains("not finished")));
    verify(registry, never()).remove(any());
    verify(orchestrator, never())
        .restartFromNode(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void incompleteRecordedOutput_isRefusedInsteadOfReplayedPartially() {
    for (int i = 0; i < 4; i++) {
      checkpoints.save(EXEC, "trigger", message("m" + i)).block();
    }

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "mid", "new-1")))
        .verifyError(IllegalArgumentException.class);

    verify(completionSink)
        .completeRestartFailure(
            eq("new-1"),
            org.mockito.ArgumentMatchers.argThat(
                e ->
                    e instanceof IllegalArgumentException
                        && e.getMessage().contains("'trigger'")
                        && e.getMessage().contains("incomplete")));
    verify(orchestrator, never())
        .restartFromNode(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void unknownNode_andUnknownExecution_reportFailures() {
    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "ghost", "new-1")))
        .verifyError(IllegalArgumentException.class);
    StepVerifier.create(processor.process(new RestartFromNodeCommand("gone", "mid", "new-2")))
        .verifyError(IllegalArgumentException.class);

    verify(completionSink).completeRestartFailure(eq("new-1"), any(IllegalArgumentException.class));
    verify(completionSink).completeRestartFailure(eq("new-2"), any(IllegalArgumentException.class));
    verify(orchestrator, never())
        .restartFromNode(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void orchestratorFailingLater_isNotReportedThroughTheCompletionSink() {
    when(registry.findByExecutionId(EXEC)).thenReturn(Optional.empty());
    when(orchestrator.restartFromNode(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Mono.error(new RuntimeException("boom")));

    StepVerifier.create(processor.process(new RestartFromNodeCommand(EXEC, "trigger", "new-1")))
        .verifyComplete();

    verify(completionSink).completeRestartSuccess("new-1");
    verify(completionSink, never()).completeRestartFailure(any(), any());
    // the failure is logged on another thread: wait, or the error lands in the next test
    ErrorGuardExtension.awaitLogged("Restarted execution from node failed", Duration.ofSeconds(2));
  }
}
