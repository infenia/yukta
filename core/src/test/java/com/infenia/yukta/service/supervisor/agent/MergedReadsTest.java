// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.control.engine.EngineReads.RunDetail;
import com.infenia.yukta.plugin.control.engine.EngineReads.RunView;
import com.infenia.yukta.plugin.control.engine.EngineReads.SchedulerView;
import com.infenia.yukta.plugin.control.engine.EngineReads.WorkflowView;
import com.infenia.yukta.plugin.input.InputRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The reads of a supervisor with several attachments are the union of their engines' reads. */
class MergedReadsTest {

  /** One attachment's reads: a run, a held run, a log, and the run's detail. */
  private static EngineReads attachment(
      final String executionId, final @Nullable Instant started, final List<String> log) {
    final RunView run = new RunView("s", executionId, "w", "RUNNING", started);
    return new EngineReads() {
      @Override
      public Flux<RunView> executions(final @Nullable String status, final int limit) {
        return Flux.just(run);
      }

      @Override
      public Flux<InputRequest> openInputs(final int limit) {
        return Flux.empty();
      }

      @Override
      public Flux<String> held() {
        return Flux.just("held-" + executionId, "shared");
      }

      @Override
      public Mono<RunDetail> execution(final String id) {
        return executionId.equals(id) ? Mono.just(new RunDetail(run, Map.of())) : Mono.empty();
      }

      @Override
      public Mono<WorkflowView> workflow(final String sessionId, final String workflowId) {
        return Mono.just(new WorkflowView(workflowId + "-" + executionId, List.of(), List.of()));
      }

      @Override
      public Flux<String> logs(final String id, final int tail) {
        return executionId.equals(id) ? Flux.fromIterable(log) : Flux.empty();
      }

      @Override
      public Mono<SchedulerView> scheduler() {
        return Mono.empty();
      }
    };
  }

  private final MergedReads merged =
      new MergedReads(
          List.of(
              attachment("e1", null, List.of("one")),
              attachment("e2", Instant.EPOCH, List.of("two", "three"))));

  @Test
  void executionsAreDistinctNewestFirstAndBounded() {
    assertThat(merged.executions(null, 10).map(RunView::executionId).collectList().block())
        .containsExactly("e2", "e1");
    assertThat(merged.executions(null, 1).collectList().block()).hasSize(1);
  }

  @Test
  void heldRunsAreDistinctAndOpenInputsAreEmptyWhenNoneIsOpen() {
    assertThat(merged.held().collectList().block())
        .containsExactlyInAnyOrder("held-e1", "shared", "held-e2");
    assertThat(merged.openInputs(10).collectList().block()).isEmpty();
  }

  @Test
  void anExecutionAndItsLogComeFromTheAttachmentThatKnowsThem() {
    assertThat(merged.execution("e2").block()).isNotNull();
    assertThat(merged.execution("none").block()).isNull();
    assertThat(merged.logs("e2", 10).collectList().block()).containsExactly("two", "three");
    assertThat(merged.logs("none", 10).collectList().block()).isEmpty();
  }

  @Test
  void aWorkflowAndTheSchedulerComeFromTheFirstAttachmentThatAnswers() {
    assertThat(merged.workflow("s", "w").block())
        .extracting(WorkflowView::workflowId)
        .isEqualTo("w-e1");
    assertThat(merged.scheduler().block()).isNull();
  }
}
