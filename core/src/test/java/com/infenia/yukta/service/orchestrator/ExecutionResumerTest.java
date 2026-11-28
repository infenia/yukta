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
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.model.control.OnLimit;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.service.ExecutionRejectedException;
import com.infenia.yukta.service.archive.DefinitionVersion;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.runtime.ExecutionScheduler;
import com.infenia.yukta.service.runtime.ExecutionSchedulerFixture;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.service.workflow.store.DefinitionRevision;
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

  /** Admission keys that never claim one. */
  private static final com.infenia.yukta.service.runtime.AdmissionKeys NO_KEYS =
      mock(com.infenia.yukta.service.runtime.AdmissionKeys.class, invocation -> Mono.empty());

  private static final Map<String, Object> PAYLOAD = Map.of("k", "v");

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
  private final InMemoryNodeCheckpointStore checkpoints = new InMemoryNodeCheckpointStore();
  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private final ExecutionScheduler scheduler =
      ExecutionSchedulerFixture.create(0, 0, 100, OnLimit.QUEUE, Duration.ofSeconds(30));
  private final PreparedWorkflow prepared = chain();
  private final ExecutionResumer resumer =
      new ExecutionResumer(log, checkpoints, definitions, orchestrator, scheduler, NO_KEYS);

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
    when(definitions.revisions("s", "w")).thenReturn(reactor.core.publisher.Flux.empty());
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
    abortedRun("old", PAYLOAD, version, finishedNodes);
  }

  private void abortedRun(
      final String id,
      final Map<String, Object> payload,
      final String version,
      final String... finishedNodes) {
    final ExecutionLogEvent started = ExecutionLogEvent.started(id, "s", "w", version, payload);
    log.append(started);
    for (final String node : finishedNodes) {
      log.append(ExecutionLogEvent.nodeFinished(id, "s", "w", node));
      checkpoints.save(id, node, DefaultMessage.create(UUID.randomUUID(), node)).block();
      checkpoints.markComplete(id, node).block();
    }
    log.append(ExecutionLogEvent.aborted(started));
  }

  @Test
  void resumedRunThatIsAbortedAgain_resumesFromWhatItRecorded() {
    // what a resume of a run that had finished a and b leaves under the new run "x": the outputs
    // of a and b carried over and marked finished (WorkflowOrchestrator.rerun)
    abortedRun(
        "x",
        Map.of("k", "v", ExecutionResumer.RESUMES_KEY, 1),
        DefinitionVersion.of(prepared),
        "a",
        "b");

    StepVerifier.create(resumer.resume("x", 3)).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<RestartPlan> plan = ArgumentCaptor.forClass(RestartPlan.class);
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(orchestrator)
        .rerun(
            eq("s"),
            eq("w"),
            eq("x"),
            anyString(),
            eq(prepared),
            plan.capture(),
            payload.capture(),
            anyMap(),
            anyString(),
            any());
    assertThat(plan.getValue().rerun()).containsExactly("c");
    assertThat(plan.getValue().boundary()).containsExactly("b");
    assertThat(payload.getValue())
        .containsEntry("k", "v")
        .containsEntry(ExecutionResumer.RESUMES_KEY, 2);
  }

  @Test
  void runThatCameFromTheMostResumesAllowed_isRefused() {
    abortedRun(
        "x",
        Map.of("k", "v", ExecutionResumer.RESUMES_KEY, 3),
        DefinitionVersion.of(prepared),
        "a");

    StepVerifier.create(resumer.resume("x", 3))
        .expectErrorSatisfies(
            e -> assertThat(e).hasMessageContaining("was resumed 3 times already"))
        .verify();
    // by hand there is no limit
    StepVerifier.create(resumer.resume("x")).expectNextCount(1).verifyComplete();
  }

  @Test
  void subWorkflowRun_isLeftToItsParentByAutoResume() {
    // the resumed parent starts the child again; resuming it too would run it twice
    abortedRun(
        "child",
        Map.of("k", "v", com.infenia.yukta.plugin.gateway.SubWorkflowGateway.PARENT_KEY, "p"),
        DefinitionVersion.of(prepared),
        "a");

    StepVerifier.create(resumer.resume("child", 3))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("is a sub-workflow run"))
        .verify();
    // by hand it can still be resumed
    StepVerifier.create(resumer.resume("child")).expectNextCount(1).verifyComplete();
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
        ExecutionSchedulerFixture.create(1, 0, 100, OnLimit.QUEUE, Duration.ofSeconds(30));

    StepVerifier.create(
            new ExecutionResumer(failing, checkpoints, definitions, orchestrator, limited, NO_KEYS)
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
            eq(Map.of("k", "v", ExecutionResumer.RESUMES_KEY, 1)),
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
        new ExecutionResumer(log, small, definitions, orchestrator, scheduler, NO_KEYS);
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
  void changedDefinition_resumesAgainstTheSavedVersionTheRunStartedWith() {
    // the run started on the graph a -> b (an older version); the current one is a -> b -> c
    final PreparedWorkflow older =
        new PreparedWorkflow(
            List.of(),
            Map.of("a", List.of(node("b")), "b", List.of()),
            Map.of("a", List.of(), "b", List.of(node("a"))),
            Map.of(),
            List.of(node("a"), node("b")),
            mock(WorkflowTemplate.class));
    abortedRun(DefinitionVersion.of(older), "a");
    final WorkflowDefinition broken = mock(WorkflowDefinition.class);
    final WorkflowDefinition other = mock(WorkflowDefinition.class);
    final WorkflowDefinition original = mock(WorkflowDefinition.class);
    when(definitions.revisions("s", "w"))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                new DefinitionRevision("r3", java.time.Instant.EPOCH),
                new DefinitionRevision("r2", java.time.Instant.EPOCH),
                new DefinitionRevision("r1", java.time.Instant.EPOCH),
                new DefinitionRevision("r0", java.time.Instant.EPOCH)));
    when(definitions.findRevision("s", "w", "r3")).thenReturn(Mono.just(broken));
    when(definitions.findRevision("s", "w", "r2")).thenReturn(Mono.just(other));
    when(definitions.findRevision("s", "w", "r1")).thenReturn(Mono.just(original));
    // a version that no longer prepares is skipped, one with another graph does not match
    when(orchestrator.prepareWorkflow(broken))
        .thenReturn(Mono.error(new IllegalStateException("plugin gone")));
    when(orchestrator.prepareWorkflow(other)).thenReturn(Mono.just(chain()));
    when(orchestrator.prepareWorkflow(original)).thenReturn(Mono.just(older));

    StepVerifier.create(resumer.resume("old")).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<RestartPlan> plan = ArgumentCaptor.forClass(RestartPlan.class);
    verify(orchestrator)
        .rerun(any(), any(), any(), any(), eq(older), plan.capture(), any(), any(), any(), any());
    assertThat(plan.getValue().rerun()).containsExactly("b");
    // the search stops at the first match
    verify(definitions, never()).findRevision("s", "w", "r0");
  }

  @Test
  void runOfWorkflowThatWasNotDurable_isRefused_evenIfWorkflowIsDurableNow() {
    final PreparedWorkflow notDurable =
        new PreparedWorkflow(
            prepared.edges(),
            prepared.adjacencyList(),
            prepared.parentsList(),
            prepared.pluginCache(),
            prepared.topologicalOrder(),
            prepared.template(),
            false);
    // the current workflow (prepared, durable) has the same graph
    abortedRun(
        "old", ExecutionResumer.logged(notDurable, PAYLOAD), DefinitionVersion.of(prepared), "a");

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("was not durable"))
        .verify();
    verify(orchestrator, never())
        .rerun(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void durableRun_isLoggedWithItsPayloadAsIs() {
    assertThat(ExecutionResumer.logged(prepared, PAYLOAD)).isSameAs(PAYLOAD);
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
        .expectErrorSatisfies(e ->
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
    final ExecutionLogEvent started =
        log.readAll().collectList().blockOptional().orElseThrow().getFirst();
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

    verify(orchestrator, timeout(2000))
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
        ExecutionSchedulerFixture.create(1, 0, 0, OnLimit.REJECT, Duration.ZERO);
    full.acquire("s", "w", 0, null);
    final ExecutionResumer limited =
        new ExecutionResumer(log, checkpoints, definitions, orchestrator, full, NO_KEYS);

    StepVerifier.create(limited.resume("old")).verifyError(ExecutionRejectedException.class);

    assertThat(log.readAll().collectList().block())
        .noneMatch(event -> event.type() == ExecutionLogEvent.Type.RUN_RESUMED);
  }
}
