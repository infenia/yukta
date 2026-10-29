// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.service.archive.DefinitionVersion;
import com.infenia.yukta.service.control.ExecutionRejectedException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.testsupport.ErrorGuardExtension;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports"
})
@ExpectedErrorLog("ERROR com.infenia.yukta.service.orchestrator.ExecutionResumer - ")
class ExecutionResumerTest {

  private static final Map<String, Object> PAYLOAD = Map.of("k", "v");

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
  private final InMemoryNodeCheckpointStore checkpoints = new InMemoryNodeCheckpointStore();
  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private final ExecutionScheduler scheduler =
      new ExecutionScheduler(0, 0, 100, ExecutionScheduler.OnLimit.QUEUE, Duration.ofSeconds(30));
  private final PreparedWorkflow prepared = chain();
  private final ExecutionResumer resumer =
      new ExecutionResumer(log, checkpoints, definitions, orchestrator, scheduler);

  private static WorkflowNode node(final String nodeId) {
    return new WorkflowNode(nodeId, "t", Map.of());
  }

  // a -> b -> c
  private static PreparedWorkflow chain() {
    return new PreparedWorkflow(
        List.of(),
        Map.of("a", List.of(node("b")), "b", List.of(node("c")), "c", List.of()),
        Map.of("a", List.of(), "b", List.of(node("a")), "c", List.of(node("b"))),
        Map.of(),
        List.of(node("a"), node("b"), node("c")),
        mock(WorkflowTemplate.class));
  }

  @BeforeEach
  void setUp() {
    final WorkflowDefinition def = mock(WorkflowDefinition.class);
    when(definitions.find("s", "w")).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(def)).thenReturn(Mono.just(prepared));
    when(orchestrator.rerun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyMap(),
            anyMap(),
            anyString(),
            any()))
        .thenReturn(Mono.empty());
  }

  private void abortedRun(final String version, final String... finishedNodes) {
    final ExecutionLogEvent started = ExecutionLogEvent.started("old", "s", "w", version, PAYLOAD);
    log.append(started);
    for (final String node : finishedNodes) {
      log.append(ExecutionLogEvent.nodeFinished("old", "s", "w", node));
      checkpoints.save("old", node, DefaultMessage.create(UUID.randomUUID(), node)).block();
      checkpoints.markComplete("old", node).block();
    }
    log.append(ExecutionLogEvent.aborted(started));
  }

  @Test
  void cancelledResume_doesNotBlockTheNextOne() {
    abortedRun(DefinitionVersion.of(prepared), "a");
    final WorkflowDefinition slowDef = mock(WorkflowDefinition.class);
    when(definitions.find("s", "w")).thenReturn(Mono.just(slowDef));
    when(orchestrator.prepareWorkflow(slowDef)).thenReturn(Mono.never());

    resumer.resume("old").subscribe().dispose();
    final WorkflowDefinition def = mock(WorkflowDefinition.class);
    when(definitions.find("s", "w")).thenReturn(Mono.just(def));
    when(orchestrator.prepareWorkflow(def)).thenReturn(Mono.just(prepared));

    StepVerifier.create(resumer.resume("old"))
        .assertNext(newRun -> assertThat(newRun).isNotEqualTo("old"))
        .verifyComplete();
  }

  @Test
  void failedLogWrite_givesTheSlotBack() {
    final InMemoryExecutionLogStore failing =
        new InMemoryExecutionLogStore() {
          @Override
          public void append(final ExecutionLogEvent event) {
            if (event.type() == ExecutionLogEvent.Type.RUN_RESUMED) {
              throw new IllegalStateException("disk full");
            }
            super.append(event);
          }
        };
    final ExecutionLogEvent started =
        ExecutionLogEvent.started("old", "s", "w", DefinitionVersion.of(prepared), PAYLOAD);
    failing.append(started);
    failing.append(ExecutionLogEvent.aborted(started));
    final ExecutionScheduler limited =
        new ExecutionScheduler(1, 0, 100, ExecutionScheduler.OnLimit.QUEUE, Duration.ofSeconds(30));

    StepVerifier.create(
            new ExecutionResumer(failing, checkpoints, definitions, orchestrator, limited)
                .resume("old"))
        .expectErrorMessage("disk full")
        .verify();

    assertThat(limited.running()).isZero();
  }

  @Test
  void rerunsOnlyUnfinishedNodes_replayingTheOutputThatFeedsThem() {
    abortedRun(DefinitionVersion.of(prepared), "a");

    StepVerifier.create(resumer.resume("old"))
        .assertNext(newRun -> assertThat(newRun).isNotEqualTo("old"))
        .verifyComplete();

    final ArgumentCaptor<RestartPlan> plan = ArgumentCaptor.forClass(RestartPlan.class);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, List<com.infenia.yukta.message.Message<?>>>> outputs =
        ArgumentCaptor.forClass(Map.class);
    verify(orchestrator)
        .rerun(
            eq("s"),
            eq("w"),
            eq("old"),
            anyString(),
            eq(prepared),
            plan.capture(),
            eq(PAYLOAD),
            outputs.capture(),
            anyString(),
            any());
    assertThat(plan.getValue().rerun()).containsExactlyInAnyOrder("b", "c");
    assertThat(plan.getValue().boundary()).containsExactly("a");
    assertThat(outputs.getValue().get("a"))
        .singleElement()
        .satisfies(m -> assertThat(m.payload()).isEqualTo("a"));
  }

  @Test
  void runCanBeResumedOnlyOnce() {
    abortedRun(DefinitionVersion.of(prepared), "a");
    assertThat(resumer.resume("old").block()).isNotNull();

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("already resumed"))
        .verify();
  }

  @Test
  void finishedNodeWhoseOutputWasTruncated_runsAgain() {
    final InMemoryNodeCheckpointStore small = new InMemoryNodeCheckpointStore(1);
    final ExecutionResumer strict =
        new ExecutionResumer(log, small, definitions, orchestrator, scheduler);
    abortedRun(DefinitionVersion.of(prepared));
    log.append(ExecutionLogEvent.nodeFinished("old", "s", "w", "a"));
    small.save("old", "a", DefaultMessage.create(UUID.randomUUID(), 1)).block();
    small.save("old", "a", DefaultMessage.create(UUID.randomUUID(), 2)).block();
    small.markComplete("old", "a").block();

    StepVerifier.create(strict.resume("old")).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<RestartPlan> plan = ArgumentCaptor.forClass(RestartPlan.class);
    verify(orchestrator)
        .rerun(any(), any(), any(), any(), any(), plan.capture(), any(), any(), any(), any());
    assertThat(plan.getValue().rerun()).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void runThatIsNotAborted_isRefused() {
    log.append(ExecutionLogEvent.started("old", "s", "w", DefinitionVersion.of(prepared), PAYLOAD));

    StepVerifier.create(resumer.resume("old")).expectError(IllegalStateException.class).verify();
    verify(orchestrator, never())
        .rerun(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void changedDefinition_isRefused() {
    abortedRun("not-the-current-version", "a");

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("changed"))
        .verify();
  }

  @Test
  void runWithoutRecordedPayload_isRefused() {
    final ExecutionLogEvent started =
        ExecutionLogEvent.started("old", "s", "w", DefinitionVersion.of(prepared));
    log.append(started);
    log.append(ExecutionLogEvent.aborted(started));

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("payload"))
        .verify();
  }

  @Test
  void unknownRun_isNotFound() {
    StepVerifier.create(resumer.resume("nope"))
        .expectError(com.infenia.yukta.service.NotFoundException.class)
        .verify();
  }

  @Test
  void workflowGone_isNotFound_withoutEchoingThePayload() {
    abortedRun(DefinitionVersion.of(prepared));
    when(definitions.find("s", "w")).thenReturn(Mono.empty());

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(com.infenia.yukta.service.NotFoundException.class)
                    .hasMessage("Workflow no longer exists: s/w"))
        .verify();
  }

  @Test
  void everyNodeFinished_isRefused() {
    abortedRun(DefinitionVersion.of(prepared), "a", "b", "c");

    StepVerifier.create(resumer.resume("old")).expectError(IllegalStateException.class).verify();
  }

  @Test
  void twoResumesAtOnce_startOnlyOneRun() {
    abortedRun(DefinitionVersion.of(prepared), "a");
    final Mono<String> first = resumer.resume("old").cache();
    first.subscribe();

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("resumed"))
        .verify();

    assertThat(first.block()).isNotNull();
    verify(orchestrator, times(1))
        .rerun(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void failedResume_releasesTheClaim_soItCanBeRetried() {
    StepVerifier.create(resumer.resume("nope")).expectError().verify();
    abortedRun(DefinitionVersion.of(prepared), "a");

    StepVerifier.create(resumer.resume("old")).expectNextCount(1).verifyComplete();
  }

  @Test
  void runResumedBeforeRestart_isRefusedFromTheLog() {
    abortedRun(DefinitionVersion.of(prepared), "a");
    final ExecutionLogEvent started = log.readAll().getFirst();
    log.append(ExecutionLogEvent.resumed(started, "new-run"));

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(
            e -> assertThat(e).hasMessageContaining("was already resumed as new-run"))
        .verify();
  }

  @Test
  void rerunThatFails_isLoggedAndDoesNotFailTheResume() {
    when(orchestrator.rerun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyMap(),
            anyMap(),
            anyString(),
            any()))
        .thenReturn(Mono.error(new IllegalStateException("rerun broke")));
    abortedRun(DefinitionVersion.of(prepared), "a");

    StepVerifier.create(resumer.resume("old")).expectNextCount(1).verifyComplete();

    verify(orchestrator, org.mockito.Mockito.timeout(2000))
        .rerun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(),
            any(),
            anyMap(),
            anyMap(),
            anyString(),
            any());
    // the error is logged on another thread after the rerun: wait, or it lands in the next test
    ErrorGuardExtension.awaitLogged("Resumed execution failed", Duration.ofSeconds(2));
  }

  @Test
  void nodeEndOnlyInLog_withoutDurableMarker_runsAgain() {
    // the log record of a node end can be lost or arrive without the output being durable; the
    // store's marker is what a resume trusts
    abortedRun(DefinitionVersion.of(prepared));
    log.append(ExecutionLogEvent.nodeFinished("old", "s", "w", "a"));
    checkpoints.save("old", "a", DefaultMessage.create(UUID.randomUUID(), "a")).block();

    StepVerifier.create(resumer.resume("old")).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<RestartPlan> plan = ArgumentCaptor.forClass(RestartPlan.class);
    verify(orchestrator)
        .rerun(any(), any(), any(), any(), any(), plan.capture(), any(), any(), any(), any());
    assertThat(plan.getValue().rerun()).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void resumeWhenTheSlotsAreFull_isRefusedBeforeAnythingIsLogged() {
    abortedRun(DefinitionVersion.of(prepared), "a");
    final ExecutionScheduler full =
        new ExecutionScheduler(1, 0, 0, ExecutionScheduler.OnLimit.REJECT, Duration.ZERO);
    full.acquire("s", "w");
    final ExecutionResumer limited =
        new ExecutionResumer(log, checkpoints, definitions, orchestrator, full);

    StepVerifier.create(limited.resume("old")).verifyError(ExecutionRejectedException.class);

    assertThat(log.readAll())
        .noneMatch(event -> event.type() == ExecutionLogEvent.Type.RUN_RESUMED);
  }
}
