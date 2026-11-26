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
import com.infenia.yukta.service.review.EngineLatency;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
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
      RunAnalyticsFixture.create(feed, Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC));

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
  void sessionThatAgedOutFreesItsPlaceForNewSessions() {
    final AtomicReference<Instant> now = new AtomicReference<>(T0);
    final Clock moving =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(final java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now.get();
          }
        };
    final RunAnalytics aging = RunAnalyticsFixture.create(new ExecutionEventFeed(), moving);
    for (int i = 0; i < 55; i++) {
      aging.accept(
          new ExecutionEvent.WorkflowStatusChanged("r" + i, "s" + i, "w" + i, SUCCESS, T0));
    }
    final Instant later = T0.plus(Duration.ofHours(30));
    now.set(later);

    aging.accept(new ExecutionEvent.WorkflowStatusChanged("fresh", "sNew", "wNew", SUCCESS, later));

    // the old sessions aged out, so the new one is tracked by name, not folded into "other"
    assertThat(aging.snapshot("session:sNew")).containsEntry("runs.finished", 1.0);
    assertThat(aging.snapshot("workflow:wNew")).containsEntry("runs.finished", 1.0);
  }

  @Test
  void runTheFeedEndedIsNotCountedAgainBySeed() {
    // with a log to seed from, as in production
    final RunAnalytics analytics =
        new RunAnalytics(
            feed, Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC), mock(ExecutionLogStore.class));
    final Instant start = T0;
    analytics.accept(new ExecutionEvent.WorkflowStatusChanged("dup", "s1", "w1", "RUNNING", start));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "dup", "s1", "w1", SUCCESS, start.plusMillis(1000)));
    final var finished =
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.RUN_FINISHED,
            "dup",
            "s1",
            "w1",
            null,
            SUCCESS,
            Map.of(),
            null,
            null,
            T0.plusSeconds(1),
            null);

    analytics.seed(List.of(finished));

    assertThat(analytics.snapshot("session:s1")).containsEntry("runs.finished", 1.0);
    // once seeded, later runs are counted as usual and nothing is remembered
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "after", "s1", "w1", "RUNNING", start.plusSeconds(5)));
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "after", "s1", "w1", SUCCESS, start.plusSeconds(6)));
    assertThat(analytics.snapshot("session:s1")).containsEntry("runs.finished", 2.0);
  }

  @Test
  void seededCommandsAreListedOldestFirstWhateverTheirOrderInTheLog() {
    final var later = control(T0.plusSeconds(20), "StopCommand");
    final var earlier = control(T0.plusSeconds(5), "PauseWorkflowCommand");

    analytics.seed(List.of(later, earlier));

    assertThat(analytics.annotations(RunAnalytics.GLOBAL, Duration.ofDays(1)))
        .extracting(RunAnalytics.Annotation::command)
        .containsExactly("PauseWorkflowCommand", "StopCommand");
  }

  @Test
  void failedReadOfTheLogIsLoggedNotLost() {
    final ExecutionLogStore broken = mock(ExecutionLogStore.class);
    when(broken.ofType(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(reactor.core.publisher.Flux.error(new IllegalStateException("log unreadable")));
    final RunAnalytics seeded = new RunAnalytics(feed, Clock.systemUTC(), broken);

    seeded.seedSafely().block();

    assertThat(seeded.snapshot(RunAnalytics.GLOBAL)).isEmpty();
  }

  @Test
  void seedReadsOnlyTheLastDay_startsFirst_neverTheWholeLog() {
    final Instant now = Instant.parse("2026-01-02T12:00:00Z");
    final Instant from = now.minus(Duration.ofHours(24));
    final ExecutionLogStore store = mock(ExecutionLogStore.class);
    when(store.ofType(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(reactor.core.publisher.Flux.empty());
    final RunAnalytics seeded = new RunAnalytics(feed, Clock.fixed(now, ZoneOffset.UTC), store);

    seeded.seedSafely().block();

    final InOrder order = Mockito.inOrder(store);
    order.verify(store).ofType(ExecutionLogEvent.Type.RUN_STARTED, from);
    order.verify(store).ofType(ExecutionLogEvent.Type.RUN_FINISHED, from);
    order.verify(store).ofType(ExecutionLogEvent.Type.RUN_ABORTED, from);
    order.verify(store).ofType(ExecutionLogEvent.Type.CONTROL_APPLIED, from);
    order.verify(store).ofType(ExecutionLogEvent.Type.CONTROL_REJECTED, from);
    Mockito.verify(store, Mockito.never()).readAll();
  }

  private static ExecutionLogEvent control(final Instant at, final String command) {
    return new ExecutionLogEvent(
        ExecutionLogEvent.Type.CONTROL_APPLIED,
        "old",
        "s1",
        "w1",
        null,
        command,
        Map.of(),
        null,
        null,
        at,
        null);
  }

  private void control(final String id, final String command, final boolean applied) {
    analytics.accept(
        new ExecutionEvent.ControlOutcome(
            id, "s1", "w1", command, null, applied, applied ? null : "no", T0.plusSeconds(510)));
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
      // the feed drops what it publishes before the reader subscribed: publish once it did
      await().atMost(10, TimeUnit.SECONDS).until(() -> feed.subscriberCount() > 0);
      feed.publish(new ExecutionEvent.WorkflowStatusChanged("x", "s1", "w1", "RUNNING", T0));
      feed.publish(
          new ExecutionEvent.WorkflowStatusChanged("x", "s1", "w1", SUCCESS, T0.plusMillis(10)));
      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(() ->
              assertThat(analytics.snapshot(RunAnalytics.GLOBAL)).containsKey("runs.SUCCESS"));
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
    RunAnalyticsFixture.create(feed).stop();

    assertThat(RunAnalyticsFixture.create(feed).liveRuns()).isEmpty();
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
        .satisfies(a -> {
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
    when(store.ofType(ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenAnswer(call ->
            call.getArgument(0) == ExecutionLogEvent.Type.RUN_FINISHED
                ? reactor.core.publisher.Flux.just(finished)
                : reactor.core.publisher.Flux.empty());
    @SuppressWarnings("unchecked")
    final ObjectProvider<ExecutionLogStore> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(store);
    final var withLog = RunAnalyticsFixture.create(new ExecutionEventFeed(), provider);
    withLog.start();
    withLog.seedFromLog();
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(withLog.snapshot("session:s1")).containsEntry("runs.finished", 1.0));
    withLog.stop();

    when(provider.getIfAvailable()).thenReturn(null);
    final var withoutLog = RunAnalyticsFixture.create(new ExecutionEventFeed(), provider);
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
        .satisfies(a -> {
          assertThat(a.command()).isEqualTo("Pause\nnow");
          assertThat(a.actor()).isNull();
        });
  }

  @Test
  void liveRunKeepsEveryFieldWhenTheNodeTotalIsFilledIn() {
    final var run =
        new RunAnalytics.LiveRun("e", "s", "w", 1L, 2L, 3, 4, 5.0, true, List.of("n"), null);

    assertThat(run.withNodesTotal(9))
        .isEqualTo(
            new RunAnalytics.LiveRun("e", "s", "w", 1L, 2L, 3, 4, 5.0, true, List.of("n"), 9));
  }

  private void latency(final EngineLatency.Stage stage, final double ms) {
    analytics.onLatency(new EngineLatency("e", "s1", "w1", stage, ms));
  }

  @Test
  void engineTimingsAreSeriesAndSnapshotValuesPerScope() {
    latency(EngineLatency.Stage.START, 10);
    latency(EngineLatency.Stage.HANDOFF, 2);
    latency(EngineLatency.Stage.HANDOFF, 8);

    assertThat(analytics.snapshot(RunAnalytics.GLOBAL))
        .containsEntry("overhead.start.p95", 10.0)
        .containsEntry("overhead.handoff.p95", 8.0);
    assertThat(analytics.snapshot("workflow:w1")).containsKey("overhead.start.p95");
    assertThat(analytics.series(RunAnalytics.GLOBAL, List.of("overhead."), HOUR))
        .containsOnlyKeys(
            "overhead.start.p50",
            "overhead.start.p95",
            "overhead.handoff.p50",
            "overhead.handoff.p95");
    assertThat(analytics.series("workflow:none", List.of("overhead."), HOUR)).isEmpty();
    // a minute without any is left out, not zero
    assertThat(analytics.series(RunAnalytics.GLOBAL, List.of("runs."), HOUR)).isEmpty();
  }

  @Test
  void engineTimingsFromTheFeedAreFollowed() {
    analytics.start();
    try {
      await().atMost(10, TimeUnit.SECONDS).until(() -> feed.subscriberCount() >= 0);
      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(() -> {
            feed.publishLatency(new EngineLatency("e", "s1", "w1", EngineLatency.Stage.START, 5));
            assertThat(analytics.snapshot(RunAnalytics.GLOBAL)).containsKey("overhead.start.p95");
          });
      assertThat(analytics.snapshot(RunAnalytics.GLOBAL)).containsKey("overhead.start.p95");
    } finally {
      analytics.stop();
    }
  }

  private void nodeOf(
      final String id,
      final String session,
      final String workflow,
      final String node,
      final int startMs,
      final int endMs,
      final String status) {
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            id, session, workflow, node, "echo", "RUNNING", Map.of(), T0.plusMillis(startMs)));
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            id, session, workflow, node, "echo", status, Map.of(), T0.plusMillis(endMs)));
  }

  @Test
  void nodesAreRankedByTheirSlowestTimeAndNameTheirSessionAndWorkflow() {
    nodeOf("e1", "s1", "w1", "fast", 0, 10, SUCCESS);
    nodeOf("e1", "s1", "w1", "slow", 0, 5000, SUCCESS);
    nodeOf("e2", "s1", "w1", "slow", 0, 7000, FAILURE);
    nodeOf("e3", "s2", "w2", "mid", 0, 300, SUCCESS);
    // ended without running: counted, never timed
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "e4", "s2", "w2", "skipped", "echo", "SKIPPED", Map.of(), T0.plusMillis(1)));

    final var rows = analytics.nodes(RunAnalytics.GLOBAL, HOUR, 10);

    assertThat(rows)
        .extracting(RunAnalytics.NodeRow::nodeId)
        .containsExactly("slow", "mid", "fast", "skipped");
    final var slow = rows.getFirst();
    assertThat(slow.sessionId()).isEqualTo("s1");
    assertThat(slow.workflowId()).isEqualTo("w1");
    assertThat(slow.module()).isEqualTo("echo");
    assertThat(slow.runs()).isEqualTo(2);
    assertThat(slow.failures()).isEqualTo(1);
    assertThat(slow.p95Ms()).isEqualTo(7000.0);
    assertThat(slow.p50Ms()).isEqualTo(5000.0);
    assertThat(slow.lastExecutionId()).isEqualTo("e2");
    assertThat(rows.getLast().p95Ms()).isZero();
    assertThat(rows.getLast().p50Ms()).isZero();
    assertThat(analytics.nodes("session:s2", HOUR, 10))
        .extracting(RunAnalytics.NodeRow::nodeId)
        .containsExactly("mid", "skipped");
    assertThat(analytics.nodes("workflow:w1", HOUR, 10)).hasSize(2);
    assertThat(analytics.nodes(RunAnalytics.GLOBAL, HOUR, 1)).hasSize(1);
    assertThat(analytics.nodes(RunAnalytics.GLOBAL, Duration.ZERO, 10)).isEmpty();
  }

  @Test
  void nodeStatsKeepAtMostFiftyNodesPerMinute() {
    for (int i = 0; i < 51; i++) {
      nodeOf("e", "s1", "w1", "n" + i, 0, 10, SUCCESS);
    }
    // a node already kept still counts after the cap is reached
    nodeOf("again", "s1", "w1", "n0", 20, 30, SUCCESS);

    final var rows = analytics.nodes(RunAnalytics.GLOBAL, HOUR, 100);

    assertThat(rows).hasSize(50);
    assertThat(rows)
        .filteredOn(r -> "n0".equals(r.nodeId()))
        .singleElement()
        .satisfies(r -> assertThat(r.runs()).isEqualTo(2));
  }

  @Test
  void liveRunNamesItsCurrentNodesAndWhetherItIsPaused() {
    analytics.accept(
        new ExecutionEvent.WorkflowStatusChanged(
            "now", "s1", "w1", "RUNNING", T0.plusSeconds(500)));
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "now", "s1", "w1", "b", "echo", "RUNNING", Map.of(), T0.plusSeconds(501)));
    analytics.accept(
        new ExecutionEvent.NodeStatusChanged(
            "now", "s1", "w1", "a", "echo", "RUNNING", Map.of(), T0.plusSeconds(501)));
    assertThat(analytics.liveRuns().getFirst().currentNodes()).containsExactly("a", "b");
    assertThat(analytics.liveRuns().getFirst().paused()).isFalse();

    control("now", "PauseWorkflowCommand", false);
    assertThat(analytics.liveRuns().getFirst().paused()).isFalse();
    control("now", "SetLimitsCommand", true);
    assertThat(analytics.liveRuns().getFirst().paused()).isFalse();
    control("other", "PauseWorkflowCommand", true);
    assertThat(analytics.liveRuns().getFirst().paused()).isFalse();
    control("now", "PauseWorkflowCommand", true);
    assertThat(analytics.liveRuns().getFirst().paused()).isTrue();
    control("now", "ResumeWorkflowCommand", true);
    assertThat(analytics.liveRuns().getFirst().paused()).isFalse();
  }

  @Test
  void seedingIgnoresInputEntries() {
    final var started = ExecutionLogEvent.started("old", "s1", "w1", "v1");
    final var input =
        ExecutionLogEvent.input("old", "s1", "w1", "n1", Map.of("event", "INPUT_REQUESTED"));
    final var other =
        RunAnalyticsFixture.create(feed, Clock.fixed(T0.plusSeconds(600), ZoneOffset.UTC));

    analytics.seed(List.of(started, input));
    other.seed(List.of(started));

    assertThat(analytics.snapshot("session:s1")).isEqualTo(other.snapshot("session:s1"));
  }

  @Test
  void droppedEventIsCounted() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry simple =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    io.micrometer.core.instrument.Metrics.addRegistry(simple);
    try {
      RunAnalytics.countDropped();
      assertThat(simple.counter("yukta.tracker.dropped", "kind", "analytics").count())
          .isEqualTo(1.0);
    } finally {
      io.micrometer.core.instrument.Metrics.removeRegistry(simple);
    }
  }
}
