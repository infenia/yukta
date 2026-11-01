// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
class RunAnalyticsTest {

  private static final Instant T0 = Instant.parse("2026-10-31T12:00:00Z");

  private static final Duration HOUR = Duration.ofHours(1);

  private static final String SUCCESS = "SUCCESS";

  private static final String FAILURE = "FAILURE";

  private static final List<String> ALL = List.of("runs.", "duration.", "queue.");

  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private final RunAnalytics analytics =
      new RunAnalytics(feed, Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC));

  private void run(
      final String id,
      final String session,
      final String workflow,
      final int startSecond,
      final int durationMs,
      final String status) {
    final Instant start = T0.plusSeconds(startSecond);
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(id, session, workflow, "RUNNING", start));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            id, session, workflow, status, start.plusMillis(durationMs)));
  }

  private void node(
      final String id,
      final String module,
      final int startMs,
      final int endMs,
      final String status) {
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            id, "s1", "w1", module + startMs, module, "RUNNING", Map.of(), T0.plusMillis(startMs)));
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            id, "s1", "w1", module + startMs, module, status, Map.of(), T0.plusMillis(endMs)));
  }

  @Test
  void percentiles_sampleTheWholeMinute_notTheFirst64Runs() {
    for (int i = 0; i < 64; i++) {
      run("fast" + i, "s1", "w1", 0, 1, SUCCESS);
    }
    for (int i = 0; i < 5000; i++) {
      run("slow" + i, "s1", "w1", 0, 1000, SUCCESS);
    }

    assertThat(analytics.snapshot(RunAnalytics.GLOBAL)).containsEntry("duration.p95", 1000.0);
  }

  @Test
  void seededControl_keepsCommandActorAndReason_typedOrFromTheText() {
    final Instant at = T0.plusSeconds(500);
    final var typed =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_REJECTED,
            "typed",
            "",
            "",
            null,
            "PauseWorkflowCommand [by key:ops]: deny",
            Map.of(),
            null,
            Map.of(
                ExecutionLogEvent.COMMAND_KEY, "PauseWorkflowCommand",
                ExecutionLogEvent.ACTOR_KEY, "key:ops",
                ExecutionLogEvent.REASON_KEY, "deny"),
            at,
            null);
    final var text =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_REJECTED,
            "text",
            "",
            "",
            null,
            "StopWorkflowCommand [by key:bob]: busy",
            Map.of(),
            null,
            null,
            at,
            null);
    final var noActor =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_APPLIED,
            "plain",
            "",
            "",
            null,
            "ResumeCommand",
            Map.of(),
            null,
            Map.of(ExecutionLogEvent.COMMAND_KEY, "ResumeCommand"),
            at,
            null);

    analytics.seed(List.of(typed, text, noActor));

    assertThat(analytics.annotations(RunAnalytics.GLOBAL, HOUR))
        .extracting(
            RunAnalytics.Annotation::command,
            RunAnalytics.Annotation::actor,
            RunAnalytics.Annotation::reason)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("PauseWorkflowCommand", "key:ops", "deny"),
            org.assertj.core.groups.Tuple.tuple("StopWorkflowCommand", "key:bob", "busy"),
            org.assertj.core.groups.Tuple.tuple("ResumeCommand", null, null));
  }

  @Test
  void countsRunsByStatusAndTimesThem() {
    run("a", "s1", "w1", 0, 1000, SUCCESS);
    run("b", "s1", "w1", 10, 3000, FAILURE);
    run("c", "s1", "w1", 20, 2000, "CANCELLED");

    final Map<String, Double> snapshot = analytics.snapshot(RunAnalytics.GLOBAL);

    assertThat(snapshot)
        .containsEntry("runs.finished", 3.0)
        .containsEntry("runs.failed", 1.0)
        .containsEntry("runs.SUCCESS", 1.0)
        .containsEntry("runs.CANCELLED", 1.0)
        .containsEntry("duration.p95", 3000.0);
    final var series = analytics.series(RunAnalytics.GLOBAL, ALL, HOUR);
    assertThat(series.get("duration.p50"))
        .extracting(MetricsSampler.Point::value)
        .containsExactly(2000.0);
    assertThat(series.get("runs.finished")).hasSize(1);
  }

  @Test
  void neverStartedRunHasNoDuration() {
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("q", "s1", "w1", "QUEUED", T0));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged("q", "s1", "w1", "CANCELLED", T0.plusSeconds(1)));

    assertThat(analytics.snapshot(RunAnalytics.GLOBAL))
        .containsEntry("runs.CANCELLED", 1.0)
        .doesNotContainKey("duration.p95");
  }

  @Test
  void queueWaitIsTheTimeFromQueuedToRunning() {
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("q", "s1", "w1", "QUEUED", T0));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged("q", "s1", "w1", "RUNNING", T0.plusMillis(1500)));
    // a second RUNNING for the same run does not count again
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged("q", "s1", "w1", "RUNNING", T0.plusMillis(2500)));

    assertThat(analytics.series(RunAnalytics.GLOBAL, List.of("queue."), HOUR).get("queue.wait.p95"))
        .extracting(MetricsSampler.Point::value)
        .containsExactly(1500.0);
  }

  @Test
  void scopesSeeOnlyTheirOwnRuns() {
    run("a", "s1", "w1", 0, 1000, SUCCESS);
    run("b", "s2", "w2", 5, 1000, FAILURE);

    assertThat(analytics.snapshot("session:s1")).containsEntry("runs.finished", 1.0);
    assertThat(analytics.snapshot("session:s1")).doesNotContainKey("runs.FAILURE");
    assertThat(analytics.snapshot("workflow:w2")).containsEntry("runs.FAILURE", 1.0);
    assertThat(analytics.snapshot("session:nope")).isEmpty();
    assertThat(analytics.series("workflow:nope", ALL, HOUR)).isEmpty();
    assertThat(analytics.heatmap("bogus", HOUR)).isEmpty();
    assertThat(analytics.modules("bogus", HOUR)).isEmpty();
  }

  @Test
  void sessionsAndWorkflowsBeyondTheLimitFoldIntoOther() {
    for (int i = 0; i < 55; i++) {
      run("r" + i, "s" + i, "w" + i, 0, 100, SUCCESS);
    }
    run("late", null, null, 0, 100, SUCCESS);

    assertThat(analytics.snapshot("session:s0")).containsEntry("runs.finished", 1.0);
    assertThat(analytics.snapshot("session:s54")).isEmpty();
    // s50..s54 and the run without a session share "other"
    assertThat(analytics.snapshot("session:other")).containsEntry("runs.finished", 6.0);
    assertThat(analytics.snapshot("workflow:other")).containsEntry("runs.finished", 6.0);
    assertThat(analytics.snapshot(RunAnalytics.GLOBAL)).containsEntry("runs.finished", 56.0);
  }

  @Test
  void heatmapHasOneCellPerBusyMinute() {
    run("a", "s1", "w1", 0, 1000, SUCCESS);
    run("b", "s1", "w1", 5, 1000, FAILURE);
    run("c", "s1", "w1", 130, 1000, SUCCESS);

    final var cells = analytics.heatmap(RunAnalytics.GLOBAL, HOUR);

    assertThat(cells).extracting(RunAnalytics.HeatCell::runs).containsExactly(2, 1);
    assertThat(cells).extracting(RunAnalytics.HeatCell::failed).containsExactly(1, 0);
    assertThat(cells.getFirst().failedRuns()).containsExactly("b");
  }

  @Test
  void modulesAreRankedByExecutions() {
    node("e1", "echo", 0, 100, SUCCESS);
    node("e1", "echo", 200, 500, FAILURE);
    node("e1", "http", 600, 900, SUCCESS);
    // a repeated end of the same node is not counted twice
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "e1", "s1", "w1", "echo0", "echo", SUCCESS, Map.of(), T0.plusMillis(950)));

    final var rows = analytics.modules(RunAnalytics.GLOBAL, HOUR);

    assertThat(rows).extracting(RunAnalytics.ModuleRow::module).containsExactly("echo", "http");
    assertThat(rows.getFirst().executions()).isEqualTo(2);
    assertThat(rows.getFirst().failures()).isEqualTo(1);
    assertThat(rows.getFirst().avgMs()).isEqualTo(200.0);
    assertThat(analytics.modules("session:s1", HOUR)).hasSize(2);
  }

  @Test
  void liveRunsShowProgressAndTheWorkflowBaseline() {
    run("old", "s1", "w1", 0, 4000, SUCCESS);
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "now", "s1", "w1", "RUNNING", T0.plusSeconds(500)));
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "now", "s1", "w1", "n1", "echo", "RUNNING", Map.of(), T0.plusSeconds(501)));
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "now", "s1", "w1", "n2", "echo", SUCCESS, Map.of(), T0.plusSeconds(502)));
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("waiting", "s1", "w1", "QUEUED", T0));

    final var live = analytics.liveRuns();

    assertThat(live).hasSize(1);
    assertThat(live.getFirst().executionId()).isEqualTo("now");
    assertThat(live.getFirst().elapsedMs()).isEqualTo(100_000);
    assertThat(live.getFirst().nodesDone()).isEqualTo(1);
    assertThat(live.getFirst().nodesSeen()).isEqualTo(2);
    assertThat(live.getFirst().p95Ms()).isEqualTo(4000.0);
  }

  @Test
  void liveRunWithoutHistoryHasNoBaseline() {
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "fresh", "s1", "w9", "RUNNING", T0.plusSeconds(1)));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "fresh2", "s1", "w1", "RUNNING", T0.plusSeconds(2)));
    run("done", "s1", "w1", 0, 1000, SUCCESS);

    assertThat(analytics.liveRuns())
        .extracting(RunAnalytics.LiveRun::p95Ms)
        .containsOnly(null, 1000.0);
  }

  @Test
  void controlCommandsBecomeAnnotationsWithinTheirScope() {
    analytics.accept(
        new ExecutionEvent.ControlOutcome(
            "a", "s1", "w1", "PauseWorkflowCommand", "guard", true, null, T0.plusSeconds(590)));
    analytics.accept(
        new ExecutionEvent.ControlOutcome(
            "b", "s2", "w2", "StopWorkflowCommand", null, false, "no", T0.plusSeconds(595)));
    analytics.accept(
        new ExecutionEvent.ControlOutcome(
            "c", null, null, "SetLimitsCommand", null, true, null, T0.plusSeconds(1)));

    assertThat(analytics.annotations(RunAnalytics.GLOBAL, Duration.ofMinutes(1)))
        .extracting(RunAnalytics.Annotation::command)
        .containsExactly("PauseWorkflowCommand", "StopWorkflowCommand");
    assertThat(analytics.annotations("session:s2", HOUR)).hasSize(1);
    assertThat(analytics.annotations("workflow:w1", HOUR)).hasSize(1);
    assertThat(analytics.annotations("workflow:none", HOUR)).isEmpty();
  }

  @Test
  void memoryStaysBounded() {
    // many runs in one minute: samples are capped, counts are not
    for (int i = 0; i < 200; i++) {
      run("r" + i, "s1", "w1", 0, i, SUCCESS);
    }
    for (int i = 0; i < 40; i++) {
      run("f" + i, "s1", "w1", 0, 5, FAILURE);
    }
    assertThat(analytics.snapshot(RunAnalytics.GLOBAL))
        .containsEntry("runs.finished", 240.0)
        .containsKey("duration.p95"); // sampled, so the value varies
    // a run a day and a minute later drops the old minute
    run("later", "s1", "w1", 25 * 3600, 10, SUCCESS);

    assertThat(analytics.snapshot(RunAnalytics.GLOBAL)).isEmpty();
  }

  @Test
  void otherEventsAreIgnoredAndTheFeedIsFollowed() {
    analytics.accept(new ExecutionEvent.EventsMissed(3, T0));
    analytics.accept(
        new ExecutionEvent.LogLine(
            "a", "s1", "w1", "n", "hi", com.infenia.yukta.plugin.core.LogLevel.INFO, T0));
    analytics.start();
    try {
      await()
          .atMost(3, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                feed.publish(
                    new ExecutionEvent.WorkflowStatusChanged("x", "s1", "w1", "RUNNING", T0));
                feed.publish(
                    new ExecutionEvent.WorkflowStatusChanged(
                        "x", "s1", "w1", SUCCESS, T0.plusMillis(10)));
                assertThat(analytics.snapshot(RunAnalytics.GLOBAL)).containsKey("runs.SUCCESS");
              });
    } finally {
      analytics.stop();
    }
    assertThat(analytics.liveRuns()).isEmpty();
  }

  @Test
  void annotationsAndQueueWaitSamplesAreCapped() {
    for (int i = 0; i < 501; i++) {
      analytics.accept(
          new ExecutionEvent.ControlOutcome(
              "a" + i, "s1", "w1", "Pause", null, true, null, T0.plusSeconds(590)));
    }
    for (int i = 0; i < 70; i++) {
      analytics.accept(new ExecutionEvent.WorkflowStatusChanged("q" + i, "s1", "w1", "QUEUED", T0));
      analytics.accept(
          new ExecutionEvent.WorkflowStatusChanged(
              "q" + i, "s1", "w1", "RUNNING", T0.plusMillis(i)));
    }

    assertThat(analytics.annotations(RunAnalytics.GLOBAL, HOUR)).hasSize(500);
    assertThat(analytics.series(RunAnalytics.GLOBAL, List.of("queue."), HOUR).get("queue.wait.p95"))
        .extracting(MetricsSampler.Point::value)
        .singleElement()
        .satisfies(v -> assertThat(v).isBetween(0.0, 69.0)); // sampled, so the value varies
  }

  @Test
  void seriesNamesFilterWhatIsReturned() {
    run("a", "s1", "w1", 0, 1000, SUCCESS);

    assertThat(analytics.series(RunAnalytics.GLOBAL, List.of("duration."), HOUR))
        .containsOnlyKeys("duration.p50", "duration.p95", "duration.p99");
    assertThat(analytics.series(RunAnalytics.GLOBAL, List.of("nothing."), HOUR)).isEmpty();
  }

  @Test
  void minutesWithoutEndedRunsAreNotHeatCellsAndNodeOdditiesAreTolerated() {
    // a node that ends without ever running (zero duration), and one that only queues
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "e", "s1", "w1", "n", "echo", "SUCCESS", Map.of(), T0));
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged("e", "s1", "w1", "m", "echo", "QUEUED", Map.of(), T0));

    assertThat(analytics.heatmap(RunAnalytics.GLOBAL, HOUR)).isEmpty();
    assertThat(analytics.modules(RunAnalytics.GLOBAL, HOUR).getFirst().avgMs()).isZero();
  }

  @Test
  void workflowWithOnlyWaitsHasNoBaseline() {
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("w", "s1", "w5", "QUEUED", T0));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged("w", "s1", "w5", "RUNNING", T0.plusSeconds(1)));

    assertThat(analytics.liveRuns())
        .extracting(RunAnalytics.LiveRun::p95Ms)
        .containsExactly((Double) null);
  }

  @Test
  void followedRunsAreCapped() {
    for (int i = 0; i <= 10_000; i++) {
      analytics.accept(
          new ExecutionEvent.WorkflowStatusChanged("r" + i, "s1", "w1", "RUNNING", T0));
    }

    final var live = analytics.liveRuns();

    assertThat(live).hasSize(10_000);
    assertThat(live).extracting(RunAnalytics.LiveRun::executionId).doesNotContain("r0");
  }

  @Test
  void activeRunSurvivesNewerRuns() {
    for (int i = 0; i < 10_000; i++) {
      analytics.accept(
          new ExecutionEvent.WorkflowStatusChanged("r" + i, "s1", "w1", "RUNNING", T0));
    }
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("r0", "s1", "w1", "RUNNING", T0));
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("rx", "s1", "w1", "RUNNING", T0));

    assertThat(analytics.liveRuns())
        .extracting(RunAnalytics.LiveRun::executionId)
        .contains("r0")
        .doesNotContain("r1");
  }

  @Test
  void stopBeforeStartIsHarmless() {
    new RunAnalytics(feed).stop();

    assertThat(new RunAnalytics(feed).liveRuns()).isEmpty();
  }

  @Test
  void seedingFromTheLogRestoresFinishedRunsAndControlCommands() {
    final var started =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_STARTED,
            "old",
            "s1",
            "w1",
            null,
            null,
            Map.of(),
            null,
            null,
            T0.plusSeconds(10),
            null);
    final var finished =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_FINISHED,
            "old",
            "s1",
            "w1",
            null,
            FAILURE,
            Map.of(),
            null,
            null,
            T0.plusSeconds(12),
            null);
    final var applied = ExecutionLogEvent.control(true, "old", "PauseWorkflowCommand", null);
    final var rejected = ExecutionLogEvent.control(false, "old", "StopCommand", "not running");
    final var tooOld =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_FINISHED,
            "ancient",
            "s1",
            "w1",
            null,
            SUCCESS,
            Map.of(),
            null,
            null,
            T0.minus(Duration.ofDays(3)),
            null);

    analytics.seed(List.of(started, tooOld, finished, applied, rejected));

    assertThat(analytics.snapshot("session:s1"))
        .containsEntry("runs.finished", 1.0)
        .containsEntry("runs.failed", 1.0)
        .containsEntry("duration.p95", 2000.0);
    assertThat(analytics.heatmap("workflow:w1", HOUR))
        .singleElement()
        .satisfies(c -> assertThat(c.failedRuns()).containsExactly("old"));
    assertThat(analytics.annotations("session:s1", Duration.ofDays(1)))
        .extracting(
            RunAnalytics.Annotation::command,
            RunAnalytics.Annotation::applied,
            RunAnalytics.Annotation::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("PauseWorkflowCommand", true, null),
            org.assertj.core.groups.Tuple.tuple("StopCommand", false, "not running"));
  }

  @Test
  void seedingSkipsIncompleteAndStaleEntries() {
    final var noStatusRun =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_FINISHED,
            "blank",
            "s1",
            "w1",
            null,
            null,
            Map.of(),
            null,
            null,
            T0.plusSeconds(12),
            null);
    final var orphanRun =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_FINISHED,
            "orphan",
            "s1",
            "w1",
            null,
            SUCCESS,
            Map.of(),
            null,
            null,
            T0.plusSeconds(12),
            null);
    final var noStatusControl =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_APPLIED,
            "blank",
            "s1",
            "w1",
            null,
            null,
            Map.of(),
            null,
            null,
            T0.plusSeconds(12),
            null);
    final var staleControl =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_APPLIED,
            "old",
            "s1",
            "w1",
            null,
            "PauseWorkflowCommand",
            Map.of(),
            null,
            null,
            T0.minus(Duration.ofDays(3)),
            null);
    final var unownedRejection =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_REJECTED,
            "nobody",
            null,
            null,
            null,
            "StopCommand",
            Map.of(),
            null,
            null,
            T0.plusSeconds(12),
            null);

    final var nodeDone =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.NODE_FINISHED,
            "orphan",
            "s1",
            "w1",
            null,
            null,
            Map.of(),
            "n1",
            null,
            T0.plusSeconds(12),
            null);
    final var aborted =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_ABORTED,
            "aborted",
            "s1",
            "w1",
            null,
            FAILURE,
            Map.of(),
            null,
            null,
            T0.plusSeconds(12),
            null);

    analytics.seed(
        List.of(
            noStatusRun,
            orphanRun,
            noStatusControl,
            staleControl,
            unownedRejection,
            nodeDone,
            aborted));

    assertThat(analytics.snapshot("session:s1"))
        .containsEntry("runs.finished", 2.0)
        .containsEntry("runs.failed", 1.0);
    assertThat(analytics.annotations(RunAnalytics.GLOBAL, Duration.ofDays(1)))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.command()).isEqualTo("StopCommand");
              assertThat(a.applied()).isFalse();
              assertThat(a.reason()).isNull();
            });
  }

  @Test
  void startSeedsFromTheDurableLogOnlyWhenThereIsOne() {
    final var finished =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_FINISHED,
            "logged",
            "s1",
            "w1",
            null,
            SUCCESS,
            Map.of(),
            null,
            null,
            Instant.now(),
            null);
    final ExecutionLogStore store = mock(ExecutionLogStore.class);
    when(store.readAll()).thenReturn(List.of(finished));
    @SuppressWarnings("unchecked")
    final ObjectProvider<ExecutionLogStore> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(store);
    final var withLog = new RunAnalytics(new ExecutionEventFeed(), provider);
    withLog.start();
    withLog.seedFromLog();
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(withLog.snapshot("session:s1")).containsEntry("runs.finished", 1.0));
    withLog.stop();

    when(provider.getIfAvailable()).thenReturn(null);
    final var withoutLog = new RunAnalytics(new ExecutionEventFeed(), provider);
    withoutLog.start();
    withoutLog.seedFromLog();
    assertThat(withoutLog.snapshot("session:s1")).doesNotContainKey("runs.finished");
    withoutLog.stop();
  }

  @Test
  void seededCommandWhoseNameSpansLinesKeepsItsWholeTextAndHasNoActor() {
    final var started =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_STARTED,
            "multi",
            "s1",
            "w1",
            null,
            null,
            Map.of(),
            null,
            null,
            T0.plusSeconds(10),
            null);
    final var applied =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.CONTROL_APPLIED,
            "multi",
            "s1",
            "w1",
            null,
            "Pause\nnow",
            Map.of(),
            null,
            null,
            T0.plusSeconds(11),
            null);

    analytics.seed(List.of(started, applied));

    assertThat(analytics.annotations(RunAnalytics.GLOBAL, Duration.ofDays(1)))
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.command()).isEqualTo("Pause\nnow");
              assertThat(a.actor()).isNull();
            });
  }

  @Test
  void liveRunKeepsEveryFieldWhenTheNodeTotalIsFilledIn() {
    final var run = new RunAnalytics.LiveRun("e", "s", "w", 1L, 2L, 3, 4, 5.0, null);

    assertThat(run.withNodesTotal(9))
        .isEqualTo(new RunAnalytics.LiveRun("e", "s", "w", 1L, 2L, 3, 4, 5.0, 9));
  }
}
