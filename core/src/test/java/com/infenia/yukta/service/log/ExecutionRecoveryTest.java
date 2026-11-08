// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.service.control.ExecutionScheduler;
import com.infenia.yukta.service.control.ExecutionSchedulerFixture;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.orchestrator.tracker.TrackerFactory;
import com.infenia.yukta.service.streaming.StatusHistoryCache;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class ExecutionRecoveryTest {

  private final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore();
  private final DefaultTaskTrackerService tracker =
      TrackerFactory.tracker(Duration.ofMinutes(10), mock(StatusHistoryCache.class));

  private void finishedRun(final String executionId, final String status) {
    store.append(ExecutionLogEvent.started(executionId, "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished(executionId, "s1", "w1", status, Map.of("n1", status)));
  }

  @Test
  void runThatNeverFinished_isRecordedAsAborted_andShownInHistory() {
    store.append(ExecutionLogEvent.started("crashed", "s1", "w1", "v1"));

    ExecutionRecoveryFixture.create(store, tracker, 100).recover();

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_ABORTED);
    StepVerifier.create(tracker.getHistory("s1"))
        .assertNext(
            history ->
                assertThat(history)
                    .singleElement()
                    .satisfies(
                        run -> {
                          assertThat(run.executionId()).isEqualTo("crashed");
                          assertThat(run.status()).isEqualTo(TaskStatus.ABORTED);
                          assertThat(run.endTime()).isNotNull();
                        }))
        .verifyComplete();
  }

  @Test
  void runWithStatusThisVersionDoesNotKnow_isSkippedAndTheOthersAreRestored() {
    finishedRun("odd", "RENAMED_IN_A_NEWER_VERSION");
    finishedRun("fine", "SUCCESS");

    ExecutionRecoveryFixture.create(store, tracker, 100).recover();

    assertThat(tracker.getProgress("s1", "fine").status()).isEqualTo(TaskStatus.SUCCESS);
    assertThat(store.readAll()).anyMatch(event -> "odd".equals(event.executionId()));
  }

  @Test
  void limitsEventThatCannotBeRead_leavesTheConfiguredLimitsAlone() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(
            9, 9, 10, ExecutionScheduler.OnLimit.REJECT, Duration.ZERO);
    store.append(
        new ExecutionLogEvent(
            ExecutionLogEvent.Type.LIMITS_CHANGED,
            ExecutionLogEvent.SCHEDULER,
            null,
            null,
            null,
            null,
            Map.of(ExecutionLogEvent.MAX_CONCURRENT, "many"),
            null,
            null,
            java.time.Instant.now(),
            null));
    final ExecutionRecovery recovery = ExecutionRecoveryFixture.create(store, tracker, 100);
    recovery.setExecutionScheduler(scheduler);

    recovery.recover();

    assertThat(scheduler.perWorkflowLimit()).isEqualTo(9);
  }

  @Test
  void startupRemovesTheDurableOutputOfRunsThatAreNoLongerInTheLog(
      @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws java.io.IOException {
    final var files =
        new com.infenia.yukta.service.store.FileNodeCheckpointStore(
            dir.toString(), tools.jackson.databind.json.JsonMapper.builder().build());
    files
        .save(
            "gone",
            "n1",
            com.infenia.yukta.message.DefaultMessage.create(java.util.UUID.randomUUID(), "x"))
        .block();
    files
        .save(
            "done",
            "n1",
            com.infenia.yukta.message.DefaultMessage.create(java.util.UUID.randomUUID(), "y"))
        .block();
    finishedRun("done", "SUCCESS");
    final ExecutionRecovery startup = new ExecutionRecovery(store, tracker, 100, files);

    startup.afterSingletonsInstantiated();

    try (var left = java.nio.file.Files.list(dir)) {
      assertThat(left.map(path -> dir.relativize(path).toString()).toList())
          .containsExactly("e-done");
    }
  }

  @Test
  void recoveryOnAliveServerLeavesTheDurableOutputAlone(
      @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws java.io.IOException {
    final var files =
        new com.infenia.yukta.service.store.FileNodeCheckpointStore(
            dir.toString(), tools.jackson.databind.json.JsonMapper.builder().build());
    files
        .save(
            "other",
            "n1",
            com.infenia.yukta.message.DefaultMessage.create(java.util.UUID.randomUUID(), "x"))
        .block();

    new ExecutionRecovery(store, tracker, 100, files).recover();

    try (var left = java.nio.file.Files.list(dir)) {
      assertThat(left.toList()).hasSize(1);
    }
  }

  @Test
  void finishedRuns_areRestoredWithTheirNodeStatuses() {
    finishedRun("done", "FAILURE");

    ExecutionRecoveryFixture.create(store, tracker, 100).recover();

    final var progress = tracker.getProgress("s1", "done");
    assertThat(progress.status()).isEqualTo(TaskStatus.FAILURE);
    assertThat(progress.tasks())
        .singleElement()
        .satisfies(t -> assertThat(t.status()).isEqualTo(TaskStatus.FAILURE));
    assertThat(progress.endTime()).isNotNull();
  }

  @Test
  void restoredRunsKeepWhoStartedThem() {
    store.append(
        ExecutionLogEvent.started(
            "mine", "s1", "w1", "v1", ExecutionLogEvent.withInitiator(Map.of(), "schedule")));
    store.append(
        ExecutionLogEvent.finished("mine", "s1", "w1", "SUCCESS", Map.of("n1", "SUCCESS")));
    store.append(ExecutionLogEvent.started("old", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished("old", "s1", "w1", "SUCCESS", Map.of("n1", "SUCCESS")));

    ExecutionRecoveryFixture.create(store, tracker, 100).recover();

    assertThat(tracker.getHistory("s1").block())
        .extracting(run -> run.executionId() + ":" + run.initiator())
        .containsExactlyInAnyOrder("mine:schedule", "old:null");
  }

  @Test
  void runThatEndedLast_isNewerThanOnesStartedAfterIt() {
    store.append(ExecutionLogEvent.started("long", "s1", "w1", "v1"));
    finishedRun("r2", "SUCCESS");
    finishedRun("r3", "SUCCESS");
    store.append(
        ExecutionLogEvent.finished("long", "s1", "w1", "SUCCESS", Map.of("n1", "SUCCESS")));

    ExecutionRecoveryFixture.create(store, tracker, 2).recover();

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .containsOnly("r3", "long");
  }

  @Test
  void retainedRunsFromSettings_keepTheMostRecentRuns() {
    finishedRun("r1", "SUCCESS");
    finishedRun("r2", "SUCCESS");
    finishedRun("r3", "SUCCESS");

    new ExecutionRecovery(
            store,
            tracker,
            YuktaSettings.bind(Map.of("yukta.execution-log.retained-runs", "2")),
            new com.infenia.yukta.service.store.InMemoryNodeCheckpointStore())
        .recover();

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsOnly("r2", "r3");
  }

  @Test
  void onlyTheMostRecentRunsAreKept_andTheLogIsCompacted() {
    finishedRun("r1", "SUCCESS");
    finishedRun("r2", "SUCCESS");
    finishedRun("r3", "SUCCESS");

    ExecutionRecoveryFixture.create(store, tracker, 2).recover();

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsOnly("r2", "r3");
    assertThat(tracker.getProgress("s1", "r1")).isNull();
    assertThat(tracker.getProgress("s1", "r3")).isNotNull();
  }

  @Test
  void anEmptyLog_isFine_andRecoveryRunsAfterTheSingletonsAreReady() {
    final ExecutionRecovery recovery = ExecutionRecoveryFixture.create(store, tracker, 0);

    recovery.afterSingletonsInstantiated();

    assertThat(store.readAll()).isEmpty();
    assertThat(tracker.getActiveSessions()).isEmpty();
  }

  @Test
  void finishEventWithoutStart_isKeptButNotRestored() {
    store.append(ExecutionLogEvent.finished("orphan", "s1", "w1", "SUCCESS", Map.of()));

    ExecutionRecoveryFixture.create(store, tracker, 100).recover();

    assertThat(store.readAll()).hasSize(1);
    assertThat(tracker.getProgress("s1", "orphan")).isNull();
  }

  @Test
  void trackerRecordsRunEndsInTheLog() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
    final DefaultTaskTrackerService live =
        TrackerFactory.tracker(
            Duration.ofMinutes(10),
            mock(StatusHistoryCache.class),
            log,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    live.init();
    StepVerifier.create(live.startWorkflow("e1", "s1", "w1", List.of("n1"))).verifyComplete();

    live.emitWorkflowStatusEvent("e1", TaskStatus.SUCCESS);

    org.awaitility.Awaitility.await()
        .atMost(5, java.util.concurrent.TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(log.readAll())
                    .singleElement()
                    .satisfies(
                        event -> {
                          assertThat(event.type()).isEqualTo(ExecutionLogEvent.Type.RUN_FINISHED);
                          assertThat(event.nodeStatuses()).containsEntry("n1", "SKIPPED");
                        }));
  }

  @Test
  void trackerLogsEachSuccessfulNode_andAbortedRunsKeepThem() {
    final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
    final DefaultTaskTrackerService live =
        TrackerFactory.tracker(
            Duration.ofMinutes(10),
            mock(StatusHistoryCache.class),
            log,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    live.init();
    StepVerifier.create(live.startWorkflow("e1", "s1", "w1", List.of("n1", "n2"))).verifyComplete();
    live.emitTaskStatusEvent("e1", "n1", "m", TaskStatus.RUNNING, Map.of());
    live.emitTaskStatusEvent("e1", "n1", "m", TaskStatus.SUCCESS, Map.of());

    org.awaitility.Awaitility.await()
        .atMost(5, java.util.concurrent.TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(log.readAll())
                    .filteredOn(e -> e.type() == ExecutionLogEvent.Type.NODE_FINISHED)
                    .singleElement()
                    .satisfies(e -> assertThat(e.nodeId()).isEqualTo("n1")));

    // the server dies here: recovery marks the run aborted and keeps what had finished
    store.append(ExecutionLogEvent.started("crashed", "s1", "w1", "v1", Map.of("k", "v")));
    store.append(ExecutionLogEvent.nodeFinished("crashed", "s1", "w1", "n1"));
    ExecutionRecoveryFixture.create(store, tracker, 100).recover();

    assertThat(store.readAll())
        .filteredOn(e -> e.type() == ExecutionLogEvent.Type.RUN_ABORTED)
        .singleElement()
        .satisfies(e -> assertThat(e.nodeStatuses()).containsEntry("n1", "SUCCESS"));
  }

  @Test
  void droppedRunsLoseTheirRecordedOutput() {
    final var checkpoints = new com.infenia.yukta.service.store.InMemoryNodeCheckpointStore();
    checkpoints
        .save(
            "r1",
            "n1",
            com.infenia.yukta.message.DefaultMessage.create(java.util.UUID.randomUUID(), "x"))
        .block();
    finishedRun("r1", "SUCCESS");
    finishedRun("r2", "SUCCESS");

    new ExecutionRecovery(store, tracker, 1, checkpoints).recover();

    StepVerifier.create(checkpoints.getAll("r1", "n1")).verifyComplete();
  }

  @Test
  void schedulerChanges_areNotRuns_theyStayAndAreTrimmedSeparately() {
    store.append(
        ExecutionLogEvent.control(true, ExecutionLogEvent.SCHEDULER, "SetLimits(1)", null));
    store.append(
        ExecutionLogEvent.control(true, ExecutionLogEvent.SCHEDULER, "SetLimits(2)", null));
    finishedRun("r1", "SUCCESS");

    ExecutionRecoveryFixture.create(store, tracker, 1).recover();

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("scheduler", "r1", "r1");
    assertThat(store.readAll().getFirst().status()).isEqualTo("SetLimits(2)");
    assertThat(tracker.getProgress("s1", "r1")).isNotNull();
  }

  @Test
  void newestLimitsChangedEvent_isReappliedToTheScheduler() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(
            9, 9, 10, ExecutionScheduler.OnLimit.REJECT, Duration.ZERO);
    store.append(ExecutionLogEvent.limitsChanged(1, 1));
    store.append(ExecutionLogEvent.limitsChanged(4, 2));
    store.append(
        ExecutionLogEvent.control(
            false, ExecutionLogEvent.SCHEDULER, "SetLimits(7, 7)", "refused"));
    final ExecutionRecovery recovery = ExecutionRecoveryFixture.create(store, tracker, 100);
    recovery.setExecutionScheduler(scheduler);

    recovery.recover();

    assertThat(scheduler.perWorkflowLimit()).isEqualTo(2);
    for (int i = 0; i < 4; i++) {
      assertThat(scheduler.acquire("s", "w" + i, 0, null).refused()).isFalse();
    }
    assertThat(scheduler.acquire("s", "w9", 0, null).refused()).isTrue();
  }

  @Test
  void auditTextAlone_doesNotChangeTheLimits() {
    final ExecutionScheduler scheduler =
        ExecutionSchedulerFixture.create(
            9, 9, 10, ExecutionScheduler.OnLimit.REJECT, Duration.ZERO);
    store.append(
        ExecutionLogEvent.control(true, ExecutionLogEvent.SCHEDULER, "SetLimits(3, 1)", null));
    final ExecutionRecovery recovery = ExecutionRecoveryFixture.create(store, tracker, 100);
    recovery.setExecutionScheduler(scheduler);

    recovery.recover();

    assertThat(scheduler.perWorkflowLimit()).isEqualTo(9);
  }

  @Test
  void recover_returnsTheIdsOfTheRunsItAborted() {
    store.append(ExecutionLogEvent.started("crashed", "s1", "w1", "v1"));
    finishedRun("done", "SUCCESS");

    assertThat(ExecutionRecoveryFixture.create(store, tracker, 100).recover())
        .containsExactly("crashed");
  }

  @Test
  void whenTheElectionRecoversOnTakeover_startupLeavesTheLogAlone() {
    store.append(ExecutionLogEvent.started("living", "s1", "w1", "v1"));
    final ExecutionRecovery recovery = ExecutionRecoveryFixture.create(store, tracker, 100);
    recovery.setLeaderElection(
        new com.infenia.yukta.service.leader.LeaderElection() {
          @Override
          public boolean isLeader() {
            return false;
          }

          @Override
          public boolean recoversOnTakeover() {
            return true;
          }
        });

    recovery.afterSingletonsInstantiated();

    assertThat(store.readAll()).hasSize(1);

    recovery.setLeaderElection(new com.infenia.yukta.service.leader.SingleServerLeader());
    recovery.afterSingletonsInstantiated();

    assertThat(store.readAll()).hasSize(2);
  }

  @Test
  void listenerIsToldWhichRunsWereInterrupted() {
    store.append(ExecutionLogEvent.started("crashed", "s1", "w1", "v1"));
    final List<List<String>> told = new java.util.ArrayList<>();
    final ExecutionRecovery recovery = ExecutionRecoveryFixture.create(store, tracker, 100);
    recovery.onRecovered(told::add);

    recovery.recover();
    recovery.recover();

    assertThat(told).containsExactly(List.of("crashed"), List.of());
  }

  @Test
  void inputEntriesOfRunAreIgnoredWhenRecovering() {
    store.append(ExecutionLogEvent.started("crashed", "s1", "w1", "v1"));
    store.append(
        ExecutionLogEvent.input(
            "crashed", "s1", "w1", "n1", Map.of("event", "INPUT_REQUESTED", "requestId", "q1")));
    finishedRun("done", "SUCCESS");
    store.append(
        ExecutionLogEvent.input(
            "done", "s1", "w1", "n1", Map.of("event", "INPUT_REQUESTED", "requestId", "q1")));

    ExecutionRecoveryFixture.create(store, tracker, 100).recover();

    assertThat(store.readAll())
        .filteredOn(e -> e.type() == ExecutionLogEvent.Type.RUN_ABORTED)
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("crashed");
    assertThat(tracker.getProgress("s1", "done").status()).isEqualTo(TaskStatus.SUCCESS);
  }
}
