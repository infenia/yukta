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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.service.archive.DefinitionVersion;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
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
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.LawOfDemeter"
})
class ExecutionResumerTest {

  private static final Map<String, Object> PAYLOAD = Map.of("k", "v");

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
  private final InMemoryNodeCheckpointStore checkpoints = new InMemoryNodeCheckpointStore();
  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private final ExecutionScheduler scheduler = mock(ExecutionScheduler.class);
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
            anyString()))
        .thenReturn(Mono.empty());
  }

  private void abortedRun(final String version, final String... finishedNodes) {
    final ExecutionLogEvent started = ExecutionLogEvent.started("old", "s", "w", version, PAYLOAD);
    log.append(started);
    for (final String node : finishedNodes) {
      log.append(ExecutionLogEvent.nodeFinished("old", "s", "w", node));
      checkpoints.save("old", node, DefaultMessage.create(UUID.randomUUID(), node)).block();
    }
    log.append(ExecutionLogEvent.aborted(started));
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
            anyString());
    assertThat(plan.getValue().rerun()).containsExactlyInAnyOrder("b", "c");
    assertThat(plan.getValue().boundary()).containsExactly("a");
    assertThat(outputs.getValue().get("a"))
        .singleElement()
        .satisfies(m -> assertThat(m.payload()).isEqualTo("a"));
  }

  @Test
  void runCanBeResumedOnlyOnce() {
    abortedRun(DefinitionVersion.of(prepared), "a");
    final String first = resumer.resume("old").block();

    StepVerifier.create(resumer.resume("old"))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining(first))
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

    StepVerifier.create(strict.resume("old")).expectNextCount(1).verifyComplete();

    final ArgumentCaptor<RestartPlan> plan = ArgumentCaptor.forClass(RestartPlan.class);
    verify(orchestrator)
        .rerun(any(), any(), any(), any(), any(), plan.capture(), any(), any(), any());
    assertThat(plan.getValue().rerun()).containsExactlyInAnyOrder("a", "b", "c");
  }

  @Test
  void runThatIsNotAborted_isRefused() {
    log.append(ExecutionLogEvent.started("old", "s", "w", DefinitionVersion.of(prepared), PAYLOAD));

    StepVerifier.create(resumer.resume("old")).expectError(IllegalStateException.class).verify();
    verify(orchestrator, never())
        .rerun(any(), any(), any(), any(), any(), any(), any(), any(), any());
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
  void everyNodeFinished_isRefused() {
    abortedRun(DefinitionVersion.of(prepared), "a", "b", "c");

    StepVerifier.create(resumer.resume("old")).expectError(IllegalStateException.class).verify();
  }
}
