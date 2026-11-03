// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.input.DefaultAnswerAuthorizer;
import com.infenia.yukta.service.orchestrator.SubWorkflowRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("PMD.TooManyStaticImports")
class SupervisorWorkflowRunnerTest {

  private static final Duration LONG = Duration.ofSeconds(5);

  private final SubWorkflowRunner child = mock(SubWorkflowRunner.class);

  private final ExecutionControlStore runs = mock(ExecutionControlStore.class);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private SupervisorWorkflowRunner runner(final int slots) {
    return new SupervisorWorkflowRunner(child, runs, meters, slots, 2);
  }

  private Mono<RunResult> run(final SupervisorWorkflowRunner runner, final Duration timeout) {
    return runner.run("b1", "sys", "wf", Map.of("event", Map.of()), "e1", timeout);
  }

  @Test
  void runCarriesDepthAndStarter() {
    when(runs.findByExecutionId("e1")).thenReturn(Optional.empty());
    when(child.run(anyString(), anyString(), any(), anyString(), anyInt()))
        .thenReturn(
            Mono.just(
                new SubWorkflowGateway.Result(
                    "c1", List.of(Map.of("commands", List.of())), "SUCCESS", "ESCALATE", "hard")));

    StepVerifier.create(run(runner(4), LONG))
        .assertNext(
            result -> {
              assertThat(result.status()).isEqualTo("SUCCESS");
              assertThat(result.outcome()).isEqualTo("ESCALATE");
              assertThat(result.reason()).isEqualTo("hard");
            })
        .verifyComplete();

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(child).run(eq("sys"), eq("wf"), payload.capture(), eq("e1"), eq(1));
    assertThat(payload.getValue())
        .containsEntry(DefaultAnswerAuthorizer.DEPTH_KEY, 1)
        .containsEntry(DefaultAnswerAuthorizer.STARTER_KEY, "b1")
        .containsKey("event");
    assertThat(meters.counter("yukta.supervisor.workflow", "outcome", "escalate").count())
        .isEqualTo(1);
  }

  @Test
  void runPastDepthLimitIsRefused() {
    final ExecutionControl control = mock(ExecutionControl.class);
    when(control.payload()).thenReturn(Map.of(DefaultAnswerAuthorizer.DEPTH_KEY, 2));
    when(runs.findByExecutionId("e1")).thenReturn(Optional.of(control));

    StepVerifier.create(run(runner(4), LONG))
        .assertNext(result -> assertThat(result.status()).isEqualTo("SKIPPED"))
        .verifyComplete();
    assertThat(meters.counter("yukta.supervisor.workflow", "outcome", "depth").count())
        .isEqualTo(1);
  }

  @Test
  void failureAndTimeoutAreResults() {
    when(runs.findByExecutionId("e1")).thenReturn(Optional.empty());
    when(child.run(anyString(), anyString(), any(), anyString(), anyInt()))
        .thenReturn(Mono.error(new IllegalStateException("boom")));
    StepVerifier.create(run(runner(4), LONG))
        .assertNext(result -> assertThat(result.status()).isEqualTo("FAILED"))
        .verifyComplete();

    when(child.run(anyString(), anyString(), any(), anyString(), anyInt()))
        .thenReturn(Mono.never());
    StepVerifier.create(run(runner(4), Duration.ofMillis(100)))
        .assertNext(result -> assertThat(result.status()).isEqualTo("TIMEOUT"))
        .verifyComplete();
  }

  @Test
  void busySlotsMakeCallerWaitThenGiveUp() {
    when(runs.findByExecutionId("e1")).thenReturn(Optional.empty());
    when(child.run(anyString(), anyString(), any(), anyString(), anyInt()))
        .thenReturn(Mono.never());
    final SupervisorWorkflowRunner runner = runner(1);
    final reactor.core.Disposable holder = run(runner, LONG).subscribe();

    StepVerifier.create(run(runner, Duration.ofMillis(200)))
        .assertNext(result -> assertThat(result.status()).isEqualTo("SKIPPED"))
        .verifyComplete();
    assertThat(runner.reservedSlots()).isEqualTo(1);
    assertThat(meters.get("yukta.supervisor.workflow.slots.used").gauge().value()).isEqualTo(1);
    holder.dispose();
  }

  @Test
  void freedSlotIsReused() {
    when(runs.findByExecutionId("e1")).thenReturn(Optional.empty());
    when(child.run(anyString(), anyString(), any(), anyString(), anyInt()))
        .thenReturn(Mono.just(new SubWorkflowGateway.Result("c", List.of())));
    final SupervisorWorkflowRunner runner = runner(1);

    StepVerifier.create(run(runner, LONG).then(run(runner, LONG)))
        .assertNext(result -> assertThat(result.status()).isEqualTo("SUCCESS"))
        .verifyComplete();
  }
}
