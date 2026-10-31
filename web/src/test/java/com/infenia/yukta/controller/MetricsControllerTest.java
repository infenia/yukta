// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.metrics.MetricsProperties;
import com.infenia.yukta.service.metrics.MetricsSampler;
import com.infenia.yukta.service.metrics.RunAnalytics;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

/** Tests for the behaviour described by {@link MetricsController}. */
@NoArgsConstructor
class MetricsControllerTest {

  private static final String GAUGE = "yukta.g";

  private static final String ONE_HOUR = "1h";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final MetricsSampler sampler =
      new MetricsSampler(
          registry,
          new MetricsProperties(
              false,
              Duration.ofSeconds(5),
              Duration.ofHours(1),
              Duration.ofHours(24),
              100,
              List.of("yukta.")));

  private final RunAnalytics analytics = new RunAnalytics(new ExecutionEventFeed());

  private final TaskTrackerService tracker = mock(TaskTrackerService.class);

  private final MetricsController controller = new MetricsController(sampler, analytics, tracker);

  private void finishedRun(final String session, final String workflow, final String status) {
    final Instant now = Instant.now();
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "e-" + session, session, workflow, "RUNNING", now.minusMillis(500)));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged("e-" + session, session, workflow, status, now));
  }

  @Test
  void answersEmptyBeforeTheFirstSample() {
    StepVerifier.create(controller.snapshot(RunAnalytics.GLOBAL))
        .assertNext(r -> assertThat(r.data().values()).isEmpty())
        .verifyComplete();
    StepVerifier.create(controller.catalog())
        .assertNext(r -> assertThat(r.data().series()).isEmpty())
        .verifyComplete();
  }

  @Test
  void seriesReturnsSampledPoints() {
    registry.gauge(GAUGE, 7);
    sampler.sample(System.currentTimeMillis());

    StepVerifier.create(controller.series(List.of(GAUGE), "15m", RunAnalytics.GLOBAL))
        .assertNext(s -> assertThat(s.data().series().get(GAUGE)).hasSize(1))
        .verifyComplete();
    StepVerifier.create(controller.snapshot(RunAnalytics.GLOBAL))
        .assertNext(s -> assertThat(s.data().values()).containsEntry(GAUGE, 7.0))
        .verifyComplete();
  }

  @Test
  void globalSeriesAndSnapshotCarryTheRunAggregatesToo() {
    finishedRun("s1", "w1", "SUCCESS");

    StepVerifier.create(controller.series(List.of("runs."), ONE_HOUR, RunAnalytics.GLOBAL))
        .assertNext(s -> assertThat(s.data().series()).containsKey("runs.finished"))
        .verifyComplete();
    StepVerifier.create(controller.snapshot(RunAnalytics.GLOBAL))
        .assertNext(s -> assertThat(s.data().values()).containsEntry("runs.finished", 1.0))
        .verifyComplete();
  }

  @Test
  void scopeShowsOnlyItsRunsAndUnknownScopeNothing() {
    registry.gauge(GAUGE, 7);
    sampler.sample(System.currentTimeMillis());
    finishedRun("s1", "w1", "SUCCESS");
    finishedRun("s2", "w2", "FAILURE");

    StepVerifier.create(controller.snapshot("session:s2"))
        .assertNext(
            s ->
                assertThat(s.data().values())
                    .containsEntry("runs.FAILURE", 1.0)
                    .doesNotContainKey("runs.SUCCESS")
                    .doesNotContainKey(GAUGE))
        .verifyComplete();
    StepVerifier.create(controller.series(List.of(GAUGE, "runs."), ONE_HOUR, "workflow:w1"))
        .assertNext(
            s -> assertThat(s.data().series()).containsKey("runs.SUCCESS").doesNotContainKey(GAUGE))
        .verifyComplete();
    StepVerifier.create(controller.series(List.of("runs."), ONE_HOUR, "session:nope"))
        .assertNext(s -> assertThat(s.data().series()).isEmpty())
        .verifyComplete();
  }

  @Test
  void heatmapModulesAnnotationsAndLiveRunsAreServed() {
    finishedRun("s1", "w1", "FAILURE");
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "e-s1", "s1", "w1", "n", "echo", "SUCCESS", Map.of(), Instant.now()));
    analytics.accept(
        new ExecutionEvent.ControlOutcome(
            "e-s1", "s1", "w1", "PauseWorkflowCommand", null, true, null, Instant.now()));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "live", "s1", "w1", "RUNNING", Instant.now().minusSeconds(5)));

    StepVerifier.create(controller.heatmap("24h", RunAnalytics.GLOBAL))
        .assertNext(
            r -> assertThat(r.data()).extracting(RunAnalytics.HeatCell::failed).containsExactly(1))
        .verifyComplete();
    StepVerifier.create(controller.modules(ONE_HOUR, RunAnalytics.GLOBAL))
        .assertNext(
            r ->
                assertThat(r.data())
                    .extracting(RunAnalytics.ModuleRow::module)
                    .containsExactly("echo"))
        .verifyComplete();
    StepVerifier.create(controller.annotations(ONE_HOUR, RunAnalytics.GLOBAL))
        .assertNext(
            r ->
                assertThat(r.data())
                    .extracting(RunAnalytics.Annotation::command)
                    .containsExactly("PauseWorkflowCommand"))
        .verifyComplete();
    final var progress = mock(WorkflowProgress.class);
    when(progress.tasks()).thenReturn(List.of(mock(TaskProgress.class), mock(TaskProgress.class)));
    when(tracker.getProgressByExecutionId("live")).thenReturn(progress);
    StepVerifier.create(controller.liveRuns())
        .assertNext(
            r ->
                assertThat(r.data())
                    .extracting(RunAnalytics.LiveRun::executionId, RunAnalytics.LiveRun::nodesTotal)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("live", 2)))
        .verifyComplete();
  }

  @Test
  void unknownRangeIsRejected() {
    StepVerifier.create(controller.series(List.of(GAUGE), "2d", RunAnalytics.GLOBAL))
        .expectErrorSatisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST))
        .verify();
    StepVerifier.create(controller.heatmap("2d", RunAnalytics.GLOBAL))
        .expectError(ResponseStatusException.class)
        .verify();
  }

  @Test
  void streamEmitsTheNewestSampleFirst() {
    registry.gauge(GAUGE, 1);
    sampler.sample(1L);

    StepVerifier.create(controller.stream(null).take(1))
        .assertNext(e -> assertThat(e.data().time()).isEqualTo(1L))
        .verifyComplete();
  }
}
