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
import com.infenia.yukta.plugin.gateway.ResultCollector;
import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "unchecked"
})
class SubWorkflowRunnerTest {

  private final WorkflowDefinitionStore definitions = mock(WorkflowDefinitionStore.class);
  private final PreparedWorkflowCache cache = mock(PreparedWorkflowCache.class);
  private final WorkflowOrchestrator orchestrator = mock(WorkflowOrchestrator.class);
  private final PreparedWorkflow prepared = mock(PreparedWorkflow.class);
  private final WorkflowDefinition definition =
      new WorkflowDefinition("child", "d", List.of(), List.of());
  private final ExecutionScheduler scheduler =
      new ExecutionScheduler(1, 0, 0, ExecutionScheduler.OnLimit.REJECT, java.time.Duration.ZERO);
  private final SubWorkflowRunner runner =
      new SubWorkflowRunner(definitions, cache, orchestrator, scheduler);

  /** A child run whose terminal delivers one message to the collector in the context. */
  private Mono<Void> delivering(final Object payload) {
    return Mono.deferContextual(
        ctx -> {
          ctx.<ResultCollector>get("resultCollector")
              .add(DefaultMessage.create(UUID.randomUUID(), payload));
          return Mono.empty();
        });
  }

  @Test
  void cachedWorkflow_runsAsChild_andReturnsTerminalResults() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(delivering("out"));

    StepVerifier.create(runner.run("s", "child", Map.of("k", "v"), "parent", 2))
        .assertNext(result -> assertThat(result.results()).containsExactly("out"))
        .verifyComplete();

    final ArgumentCaptor<Map<String, Object>> start = ArgumentCaptor.forClass(Map.class);
    verify(orchestrator)
        .execute(eq("s"), eq("child"), anyString(), eq(prepared), start.capture(), any());
    assertThat(start.getValue())
        .containsEntry("k", "v")
        .containsEntry(SubWorkflowGateway.DEPTH_KEY, 2)
        .containsEntry(SubWorkflowGateway.PARENT_KEY, "parent");
  }

  @Test
  void anUncachedWorkflow_isPrepared_andCachedOnlyWhileItIsStillTheStoredOne() {
    when(cache.get("s", "child")).thenReturn(Optional.empty());
    when(definitions.find("s", "child")).thenReturn(Mono.just(definition));
    when(orchestrator.prepareWorkflow(definition)).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(Mono.empty());

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .assertNext(result -> assertThat(result.results()).isEmpty())
        .verifyComplete();
    verify(cache).put(eq("s"), eq("child"), eq(prepared), any());

    final WorkflowDefinition newer = new WorkflowDefinition("child", "newer", List.of(), List.of());
    when(definitions.find("s", "child")).thenReturn(Mono.just(definition), Mono.just(newer));
    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .expectNextCount(1)
        .verifyComplete();
    verify(cache, org.mockito.Mockito.times(1)).put(any(), any(), any(), any());
  }

  @Test
  void unknownWorkflow_isNotFound_andTooDeepNestingIsRefused() {
    when(cache.get("s", "nope")).thenReturn(Optional.empty());
    when(definitions.find("s", "nope")).thenReturn(Mono.empty());

    StepVerifier.create(runner.run("s", "nope", Map.of(), "parent", 1))
        .verifyError(NotFoundException.class);
    StepVerifier.create(
            runner.run("s", "child", Map.of(), "parent", SubWorkflowGateway.MAX_DEPTH + 1))
        .verifyError(IllegalStateException.class);
    verify(orchestrator, never())
        .execute(anyString(), anyString(), anyString(), any(), anyMap(), any());
  }

  @Test
  void failingChild_failsTheCaller_withItsOwnMessage_andTellsWhichRunFailedHow() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(Mono.error(new IllegalStateException("child failed")));

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .verifyErrorSatisfies(
            e -> {
              assertThat(e).isInstanceOf(SubWorkflowGateway.ChildFailed.class);
              assertThat(e).hasMessage("child failed");
              final SubWorkflowGateway.ChildFailed failed = (SubWorkflowGateway.ChildFailed) e;
              assertThat(failed.status()).isEqualTo("FAILURE");
              assertThat(failed.executionId()).isNotBlank();
            });
  }

  @Test
  void childThatTimesOut_failsAsTimedOut() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(
            Mono.error(
                new com.infenia.yukta.service.orchestrator.compiler.ExecutionTimeoutException(5)));

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .verifyErrorSatisfies(
            e -> assertThat(((SubWorkflowGateway.ChildFailed) e).status()).isEqualTo("TIMED_OUT"));
  }

  @Test
  void childThatWaitedTooLongForSlot_failsAsTimedOut() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(
            Mono.error(new com.infenia.yukta.service.control.QueueTimeoutException("no slot")));

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .verifyErrorSatisfies(
            e -> assertThat(((SubWorkflowGateway.ChildFailed) e).status()).isEqualTo("TIMED_OUT"));
  }

  @Test
  void grandchildsFailure_isReportedAsThisChilds() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(
            Mono.error(
                new SubWorkflowGateway.ChildFailed(
                    "grandchild", "FAILURE", new IllegalStateException("deep"))));

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .verifyErrorSatisfies(
            e -> {
              assertThat(e).hasMessage("deep");
              assertThat(((SubWorkflowGateway.ChildFailed) e).executionId())
                  .isNotEqualTo("grandchild");
            });
  }

  @Test
  void theOutcomeTerminalTheChildReached_isReported() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(
            Mono.deferContextual(
                ctx -> {
                  final ResultCollector collector = ctx.get("resultCollector");
                  collector.addCompletion("done", Map.of("reached", true));
                  collector.addCompletion(
                      "escalate",
                      Map.of("reached", true, "outcome", "ESCALATE", "reason", "flaky"));
                  return Mono.empty();
                }));

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .assertNext(
            result -> {
              assertThat(result.status()).isEqualTo("SUCCESS");
              assertThat(result.outcome()).isEqualTo("ESCALATE");
              assertThat(result.reason()).isEqualTo("flaky");
            })
        .verifyComplete();
  }

  @Test
  void childThatReachedNoOutcomeTerminal_hasNoOutcome() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(delivering("out"));

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .assertNext(
            result -> {
              assertThat(result.outcome()).isNull();
              assertThat(result.reason()).isNull();
            })
        .verifyComplete();
  }

  @Test
  void childTakesNoSlotOfItsOwn_soSingleSlotParentCannotStarveIt() {
    scheduler.acquire("s", "parent"); // the parent holds the only slot
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenAnswer(
            call -> {
              final ExecutionScheduler.Admission admission = call.getArgument(5);
              return admission.permit().doOnNext(ExecutionScheduler.Permit::release).then();
            });

    StepVerifier.create(runner.run("s", "child", Map.of(), "parent", 1))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(scheduler.running()).isEqualTo(1);
  }

  @Test
  void cancellingTheCaller_cancelsTheChild() {
    when(cache.get("s", "child")).thenReturn(Optional.of(prepared));
    final java.util.concurrent.atomic.AtomicBoolean cancelled =
        new java.util.concurrent.atomic.AtomicBoolean();
    when(orchestrator.execute(anyString(), anyString(), anyString(), any(), anyMap(), any()))
        .thenReturn(Mono.<Void>never().doOnCancel(() -> cancelled.set(true)));

    final reactor.core.Disposable run = runner.run("s", "child", Map.of(), "parent", 1).subscribe();
    org.awaitility.Awaitility.await()
        .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
        .until(this::mockingInvoked);
    run.dispose();

    org.awaitility.Awaitility.await()
        .atMost(2, java.util.concurrent.TimeUnit.SECONDS)
        .untilTrue(cancelled);
    assertThat(cancelled).isTrue();
  }

  private boolean mockingInvoked() {
    return !org.mockito.Mockito.mockingDetails(orchestrator).getInvocations().isEmpty();
  }
}
