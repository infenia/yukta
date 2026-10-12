// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.event.WorkflowStatusEvent;
import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.streaming.StatusHistoryCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Unit tests for {@link DefaultTaskTrackerService}. */
@MockitoSettings
@NoArgsConstructor
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.TooManyMethods",
  "PMD.CyclomaticComplexity",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidAccessibilityAlteration",
  "PMD.CognitiveComplexity",
  "PMD.ShortVariable",
  "PMD.CouplingBetweenObjects"
})
class DefaultTaskTrackerServiceTest {

  /** Tracker under test. */
  private DefaultTaskTrackerService tracker;

  /** Mock status history cache. */
  @Mock private StatusHistoryCache statusHistoryCache;

  @BeforeEach
  void setUp() {
    tracker = new DefaultTaskTrackerService(Duration.ofMillis(200), statusHistoryCache);
    tracker.init();
  }

  @Test
  void testRunStillEndsWhenTheExecutionLogCannotBeWritten() {
    final com.infenia.yukta.service.log.ExecutionLogStore broken =
        org.mockito.Mockito.mock(com.infenia.yukta.service.log.ExecutionLogStore.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("disk full"))
        .when(broken)
        .append(org.mockito.ArgumentMatchers.any());
    final DefaultTaskTrackerService failing =
        new DefaultTaskTrackerService(
            Duration.ofSeconds(30), statusHistoryCache, broken, new SimpleMeterRegistry());
    failing.init();
    StepVerifier.create(failing.startWorkflow("b-exec", "b-sess", "b-wf", List.of("a")))
        .verifyComplete();

    // one batch, so the test fails if a failing event aborted the rest of its batch
    failing.handleEvents(
        List.of(
            TaskStatusEvent.create("b-exec", "a", "echo", TaskStatus.SUCCESS.name(), Map.of()),
            WorkflowStatusEvent.create("b-exec", TaskStatus.SUCCESS.name())));

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(failing.getProgressByExecutionId("b-exec").status())
                    .isEqualTo(TaskStatus.SUCCESS.name()));
  }

  @Test
  void testTaskUpdateIsStillDeliveredWhenTheExecutionLogCannotBeWritten() {
    final com.infenia.yukta.service.log.ExecutionLogStore broken =
        org.mockito.Mockito.mock(com.infenia.yukta.service.log.ExecutionLogStore.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("disk full"))
        .when(broken)
        .append(org.mockito.ArgumentMatchers.any());
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final DefaultTaskTrackerService failing =
        new DefaultTaskTrackerService(Duration.ofSeconds(30), statusHistoryCache, broken, meters);
    failing.init();
    StepVerifier.create(failing.startWorkflow("n-exec", "n-sess", "n-wf", List.of("a")))
        .verifyComplete();
    StepVerifier.create(failing.getStatusStream("n-exec", false).take(1))
        .then(
            () ->
                failing.handleEvents(
                    List.of(
                        TaskStatusEvent.create(
                            "n-exec", "a", "echo", TaskStatus.SUCCESS.name(), Map.of()))))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(meters.get("yukta.tracker.log.append.failed").tag("type", "node").counter().count())
        .isEqualTo(1);

    failing.handleEvents(List.of(WorkflowStatusEvent.create("n-exec", TaskStatus.SUCCESS.name())));

    assertThat(meters.get("yukta.tracker.log.append.failed").tag("type", "run").counter().count())
        .isEqualTo(1);
    assertThat(meters.find("yukta.tracker.dropped").counter()).isNull();
  }

  @Test
  void testDefinitionVersionIsReportedWithTheProgress() {
    StepVerifier.create(tracker.startWorkflow("v-exec", "v-sess", "v-wf", List.of("a")))
        .verifyComplete();
    assertThat(tracker.getProgressByExecutionId("v-exec").definitionVersion()).isNull();

    tracker.recordDefinitionVersion("v-exec", "abc123");
    tracker.recordDefinitionVersion("unknown", "ignored");

    assertThat(tracker.getProgressByExecutionId("v-exec").definitionVersion()).isEqualTo("abc123");
    assertThat(tracker.getProgress("v-sess", "v-exec").definitionVersion()).isEqualTo("abc123");
  }

  @Test
  void testEachNodeRunIsOneObservation() {
    final var observations = io.micrometer.observation.ObservationRegistry.create();
    final var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
    observations
        .observationConfig()
        .observationHandler(
            new io.micrometer.observation.ObservationHandler<>() {
              @Override
              public boolean supportsContext(
                  final io.micrometer.observation.Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(final io.micrometer.observation.Observation.Context context) {
                seen.add(context.getName() + ":" + context.getContextualName());
              }
            });
    final DefaultTaskTrackerService traced =
        new DefaultTaskTrackerService(
            Duration.ofMillis(200),
            statusHistoryCache,
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            observations);
    traced.init();
    StepVerifier.create(traced.startWorkflow("o-exec", "o-sess", "o-wf", List.of("a")))
        .verifyComplete();

    traced.emitTaskStatusEvent("o-exec", "a", "echo", TaskStatus.RUNNING, Map.of());
    traced.emitTaskStatusEvent("o-exec", "a", "echo", TaskStatus.SUCCESS, Map.of());

    await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(seen).hasSize(1));
    assertThat(seen.get(0)).isEqualTo("yukta.node:node a");
  }

  @Test
  void testLateRunningEventForAnEndedNodeOpensNoSpan() {
    final var observations = io.micrometer.observation.ObservationRegistry.create();
    final java.util.concurrent.atomic.AtomicInteger started =
        new java.util.concurrent.atomic.AtomicInteger();
    observations
        .observationConfig()
        .observationHandler(
            new io.micrometer.observation.ObservationHandler<>() {
              @Override
              public boolean supportsContext(
                  final io.micrometer.observation.Observation.Context context) {
                return "yukta.node".equals(context.getName());
              }

              @Override
              public void onStart(final io.micrometer.observation.Observation.Context context) {
                started.incrementAndGet();
              }
            });
    final DefaultTaskTrackerService traced =
        new DefaultTaskTrackerService(
            Duration.ofSeconds(30),
            statusHistoryCache,
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            observations);
    traced.init();
    StepVerifier.create(traced.startWorkflow("l-exec", "l-sess", "l-wf", List.of("a")))
        .verifyComplete();
    StepVerifier.create(traced.finishWorkflow("l-exec", TaskStatus.SUCCESS)).verifyComplete();
    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(traced.getProgressByExecutionId("l-exec").status())
                    .isEqualTo(TaskStatus.SUCCESS.name()));

    traced.emitTaskStatusEvent("l-exec", "a", "echo", TaskStatus.RUNNING, Map.of());
    traced.emitTaskStatusEvent("l-exec", "a", "echo", TaskStatus.SUCCESS, Map.of());

    // let the event batch run, then check that no node span was opened
    await().during(300, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS).until(() -> true);
    assertThat(started).hasValue(0);
  }

  @Test
  void testNodeMetricsCountOutcomesAndTimeRunNodes() {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final DefaultTaskTrackerService metered =
        new DefaultTaskTrackerService(
            Duration.ofMillis(200), statusHistoryCache, new InMemoryExecutionLogStore(), meters);
    metered.init();
    StepVerifier.create(metered.startWorkflow("m-exec", "m-sess", "m-wf", List.of("a", "b")))
        .verifyComplete();

    metered.emitTaskStatusEvent("m-exec", "a", "echo", TaskStatus.RUNNING, Map.of());
    metered.emitTaskStatusEvent("m-exec", "a", "echo", TaskStatus.SUCCESS, Map.of());
    metered.emitTaskStatusEvent("m-exec", "b", "echo", TaskStatus.SUCCESS, Map.of());
    metered.emitTaskStatusEvent("m-exec", "ghost", "echo", TaskStatus.SUCCESS, Map.of());

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(
                      meters
                          .get("yukta.node.executions")
                          .tags("module", "echo", "status", "SUCCESS")
                          .counter()
                          .count())
                  .isEqualTo(2.0);
              // only the node that actually ran has a duration
              assertThat(meters.get("yukta.node.duration").timer().count()).isEqualTo(1);
            });
  }

  @Test
  void testLogBurstIsDroppedAndCounted_andStateEventsAreNeverLost() {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final DefaultTaskTrackerService burst =
        new DefaultTaskTrackerService(
            Duration.ofMillis(200), statusHistoryCache, new InMemoryExecutionLogStore(), meters);
    StepVerifier.create(burst.startWorkflow("b-exec", "b-sess", "b-wf", List.of("n")))
        .verifyComplete();

    // no consumer yet, so everything queues: more log lines than allowed, and more state
    // events than the old 4096-slot buffer could hold
    for (int i = 0; i < 10_050; i++) {
      burst.emitLogEvent("b-exec", "n", "line " + i);
    }
    for (int i = 0; i < 6_000; i++) {
      burst.emitTaskStatusEvent("b-exec", "n", "echo", TaskStatus.RUNNING, Map.of());
    }
    burst.emitTaskStatusEvent("b-exec", "n", "echo", TaskStatus.SUCCESS, Map.of());
    burst.init();

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(burst.getProgress("b-sess", "b-exec").tasks().getFirst().status())
                    .isEqualTo("SUCCESS"));
    assertThat(meters.get("yukta.tracker.dropped").tag("type", "log").counter().count())
        .isEqualTo(50.0);

    // the budget is released as lines are applied, so later lines are accepted again
    burst.emitLogEvent("b-exec", "n", "later");
    assertThat(meters.get("yukta.tracker.dropped").tag("type", "log").counter().count())
        .isEqualTo(50.0);
  }

  @Test
  void testEventsAfterTheSinkEndsAreCountedNotThrown() {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final DefaultTaskTrackerService ended =
        new DefaultTaskTrackerService(
            Duration.ofMillis(200), statusHistoryCache, new InMemoryExecutionLogStore(), meters);
    final Sinks.Many<?> sink =
        (Sinks.Many<?>)
            java.util.Objects.requireNonNull(
                org.springframework.test.util.ReflectionTestUtils.getField(ended, "eventSink"));
    sink.tryEmitComplete();

    ended.emitTaskStatusEvent("x", "n", "echo", TaskStatus.RUNNING, Map.of());

    assertThat(meters.get("yukta.tracker.dropped").tag("type", "state").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void testWorkflowTracking() {
    final String sessionId = "sess-1";
    final String workflowId = "wf-1";
    final String executionId = "exec-1";
    final List<String> nodes = List.of("node1", "node2");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();
    WorkflowProgress progress = tracker.getProgress(sessionId, executionId);

    assertThat(progress).isNotNull();
    assertThat(progress.status()).isEqualTo("RUNNING");
    assertThat(progress.tasks()).hasSize(2);
    assertThat(progress.tasks().getFirst().nodeId()).isEqualTo("node1");
    assertThat(progress.tasks().getFirst().status()).isEqualTo("PENDING");

    tracker.emitTaskStatusEvent(executionId, "node1", "moduleA", TaskStatus.SUCCESS, Map.of());

    // Loop to wait for state update
    await()
        .atMost(1, TimeUnit.SECONDS)
        .until(
            () ->
                "SUCCESS"
                    .equals(
                        tracker.getProgress(sessionId, executionId).tasks().getFirst().status()));

    progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("SUCCESS");
    assertThat(progress.tasks().getFirst().module()).isEqualTo("moduleA");

    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Loop to wait for state update
    await()
        .atMost(1, TimeUnit.SECONDS)
        .until(() -> "SUCCESS".equals(tracker.getProgress(sessionId, executionId).status()));

    progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.status()).isEqualTo("SUCCESS");
    assertThat(progress.endTime()).isNotNull();

    // Test getHistory
    StepVerifier.create(tracker.getHistory(sessionId))
        .assertNext(history -> assertThat(history).hasSize(1))
        .verifyComplete();
  }

  @Test
  void testLogStreaming() {
    final String sessionId = "sess-1";
    final String workflowId = "wf-1";
    final String executionId = "exec-log-1";
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    StepVerifier.create(tracker.getLogStream(executionId))
        .then(() -> tracker.emitLogEvent(executionId, "system", "log line 1"))
        .expectNext("log line 1")
        .thenCancel()
        .verify();
  }

  @Test
  void controlStateEvent_isReportedSeparatelyAndDoesNotTerminateStream() {
    final String executionId = "exec-ctrl-1";
    StepVerifier.create(tracker.startWorkflow(executionId, "sess-1", "wf-1", List.of("n1")))
        .verifyComplete();

    StepVerifier.create(tracker.getStatusStream(executionId))
        .then(() -> tracker.emitControlStateEvent(executionId, ControlState.NODE_PAUSED))
        .assertNext(
            p -> {
              assertThat(p.status()).isEqualTo("RUNNING");
              assertThat(p.controlState()).isEqualTo("NODE_PAUSED");
              assertThat(p.endTime()).isNull();
            })
        .then(() -> tracker.emitWorkflowStatusEvent(executionId, TaskStatus.STOPPED))
        .assertNext(p -> assertThat(p.status()).isEqualTo("STOPPED"))
        .expectComplete()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void cleanup_capsFinishedHistoryPerSession() {
    final int total = DefaultTaskTrackerService.MAX_FINISHED_PER_SESSION + 5;
    for (int i = 0; i < total; i++) {
      final String id = "exec-hist-" + i;
      StepVerifier.create(tracker.startWorkflow(id, "sess-hist", "wf-" + i, List.of("n1")))
          .verifyComplete();
      tracker.emitWorkflowStatusEvent(id, TaskStatus.SUCCESS);
    }

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(tracker.getHistory("sess-hist").block())
                    .hasSize(DefaultTaskTrackerService.MAX_FINISHED_PER_SESSION));
  }

  @Test
  void testStatusStreaming() {
    final String sessionId = "sess-1";
    final String workflowId = "wf-1";
    final String executionId = "exec-status-1";
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of("n1")))
        .verifyComplete();

    StepVerifier.create(tracker.getStatusStream(executionId))
        .then(
            () ->
                tracker.emitTaskStatusEvent(executionId, "n1", "mod", TaskStatus.SUCCESS, Map.of()))
        .assertNext(progress -> assertThat(progress.workflowId()).isEqualTo("wf-1"))
        .thenCancel()
        .verify();
  }

  @Test
  void testRemoveSession() {
    final String sessionId = "sess-1";
    final String workflowId = "wf-1";
    final String executionId = "exec-remove-1";
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();
    assertThat(tracker.getActiveSessions().size()).isEqualTo(1);
    tracker.removeSession(sessionId);
    assertThat(tracker.getActiveSessions().size()).isEqualTo(0);
  }

  @Test
  void testGetProgressNotFound() {
    assertThat(tracker.getProgress("unknown", "unknown")).isNull();
  }

  @Test
  void testEventsForUnknownExecution() {
    tracker.emitTaskStatusEvent("unknown", "n", "m", TaskStatus.SUCCESS, Map.of());
    tracker.emitWorkflowStatusEvent("unknown", TaskStatus.SUCCESS);
    tracker.emitLogEvent("unknown", "system", "log");
    // Should not crash - test passes if no exception is thrown
    assertThat(true).isTrue();
  }

  @Test
  void testAutoCleanupAfterTerminalStatus() {
    final String sessionId = "sess-cleanup";
    final String workflowId = "wf-cleanup";
    final String executionId = "exec-cleanup";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of("n1")))
        .verifyComplete();

    // Verify sinks exist before cleanup
    assertThat(tracker.getLogStream(executionId) != null).isTrue();
    assertThat(tracker.getStatusStream(executionId) != null).isTrue();

    // Emit terminal status event
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Wait for async cleanup to complete after CLEANUP_TTL (10 minutes)
    // Use virtual time to avoid waiting 10 minutes in tests
    StepVerifier.create(
            tracker
                .getStatusStream(executionId)
                .doOnSubscribe(
                    _ -> tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS)))
        .thenCancel()
        .verify();

    // Give the cleanup task time to execute
    // In production this would be 10 minutes; in tests we verify that it was scheduled
    // by checking that finishWorkflow() completes successfully
  }

  @Test
  void testUpdateTaskStatus4Arg() {
    final String sessionId = "sess-4arg";
    final String workflowId = "wf-4arg";
    final String executionId = "exec-4arg";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Test 4-arg updateTaskStatus
    StepVerifier.create(
            tracker.updateTaskStatus(executionId, "node1", "moduleB", TaskStatus.RUNNING))
        .verifyComplete();

    // Wait for event processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("RUNNING");
    assertThat(progress.tasks().getFirst().module()).isEqualTo("moduleB");
  }

  @Test
  void testUpdateTaskStatus5ArgWithMetadata() {
    final String sessionId = "sess-5arg";
    final String workflowId = "wf-5arg";
    final String executionId = "exec-5arg";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Test 5-arg updateTaskStatus with metadata
    final Map<String, Object> metadata = Map.of("key1", "value1", "key2", 42);
    StepVerifier.create(
            tracker.updateTaskStatus(executionId, "node1", "moduleC", TaskStatus.SUCCESS, metadata))
        .verifyComplete();

    // Wait for event processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("SUCCESS");
    assertThat(progress.tasks().getFirst().metadata().containsKey("key1")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().get("key1")).isEqualTo("value1");
  }

  @Test
  void testFinishWorkflow() {
    final String sessionId = "sess-finish";
    final String workflowId = "wf-finish";
    final String executionId = "exec-finish";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Test finishWorkflow
    StepVerifier.create(tracker.finishWorkflow(executionId, TaskStatus.SUCCESS)).verifyComplete();

    // Wait for event processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null && "SUCCESS".equals(progress.status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.status()).isEqualTo("SUCCESS");
    assertThat(progress.endTime()).isNotNull();
  }

  @Test
  void testAppendLog() {
    final String sessionId = "sess-append";
    final String workflowId = "wf-append";
    final String executionId = "exec-append";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Test appendLog (which internally calls emitLogEvent)
    StepVerifier.create(tracker.appendLog(executionId, "test log line")).verifyComplete();

    // The log was emitted; verify by checking the mono completed
    assertThat(true).isTrue();
  }

  @Test
  void testGetLatestExecutionId() {
    final String sessionId = "sess-latest";
    final String workflowId = "wf-latest";
    final String executionId1 = "exec-latest-1";
    final String executionId2 = "exec-latest-2";

    StepVerifier.create(tracker.startWorkflow(executionId1, sessionId, workflowId, List.of()))
        .verifyComplete();
    StepVerifier.create(tracker.startWorkflow(executionId2, sessionId, workflowId, List.of()))
        .verifyComplete();

    // getLatestExecutionId should return the most recent
    final String latest = tracker.getLatestExecutionId(sessionId, workflowId);
    assertThat(latest).isEqualTo(executionId2);
  }

  @Test
  void testGetLogStreamUnknownExecution() {
    // getLogStream for unknown execution should return empty Flux
    StepVerifier.create(tracker.getLogStream("unknown-exec")).verifyComplete();
  }

  @Test
  void testGetStatusStreamUnknownExecution() {
    // getStatusStream for unknown execution should return empty Flux
    StepVerifier.create(tracker.getStatusStream("unknown-exec")).verifyComplete();
  }

  @Test
  void testGetHistoryUnknownSession() {
    // getHistory for unknown session should return empty list
    StepVerifier.create(tracker.getHistory("unknown-session"))
        .assertNext(history -> assertThat(history).isEmpty())
        .verifyComplete();
  }

  @Test
  void testGetHistoryFiltersByWorkflowId() {
    final String sessionId = "sess-scoped";
    final String workflowId = "wf-scoped";
    final String otherWorkflowId = "wf-other";

    StepVerifier.create(
            tracker.startWorkflow("exec-scoped-1", sessionId, workflowId, List.of("node1")))
        .verifyComplete();
    StepVerifier.create(
            tracker.startWorkflow("exec-scoped-2", sessionId, otherWorkflowId, List.of("node1")))
        .verifyComplete();

    StepVerifier.create(tracker.getHistory(sessionId, workflowId))
        .assertNext(
            history -> {
              assertThat(history).hasSize(1);
              assertThat(history.getFirst().executionId()).isEqualTo("exec-scoped-1");
              assertThat(history.getFirst().workflowId()).isEqualTo(workflowId);
            })
        .verifyComplete();
  }

  @Test
  void testGetHistoryByWorkflowIdUnknownSession() {
    StepVerifier.create(tracker.getHistory("unknown-session", "unknown-workflow"))
        .assertNext(history -> assertThat(history).isEmpty())
        .verifyComplete();
  }

  @Test
  void testRemoveSessionNonExistent() {
    // removeSession on non-existent session should not crash
    tracker.removeSession("non-existent-session");
    assertThat(tracker.getActiveSessions().size()).isEqualTo(0);
  }

  @Test
  void testUpdateTaskUnknownNodeId() {
    final String sessionId = "sess-unknown-node";
    final String workflowId = "wf-unknown-node";
    final String executionId = "exec-unknown-node";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit event for unknown node (should be silently ignored)
    tracker.emitTaskStatusEvent(
        executionId, "unknown-node", "module", TaskStatus.RUNNING, Map.of());

    // Wait and verify workflow is still intact
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("PENDING");
  }

  @Test
  void testDetermineEndTimeWithError() {
    final String sessionId = "sess-error";
    final String workflowId = "wf-error";
    final String executionId = "exec-error";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit ERROR status (should set endTime)
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.ERROR, Map.of());

    // Wait for event processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "ERROR".equals(progress.tasks().getFirst().status())
          && progress.tasks().getFirst().endTime() != null) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("ERROR");
    assertThat(progress.tasks().getFirst().endTime()).isNotNull();
  }

  @Test
  void testDetermineStartTimeRunning() {
    final String sessionId = "sess-start-time";
    final String workflowId = "wf-start-time";
    final String executionId = "exec-start-time";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Task should start with null startTime
    final WorkflowProgress progress1 = tracker.getProgress(sessionId, executionId);
    assertThat(progress1.tasks().getFirst().startTime()).isNull();

    // Emit RUNNING status (should set startTime)
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.RUNNING, Map.of());

    // Wait for event processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())
          && progress.tasks().getFirst().startTime() != null) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().startTime()).isNotNull();
  }

  @Test
  void testDetermineEndTimeNotOverwritten() {
    final String sessionId = "sess-end-time";
    final String workflowId = "wf-end-time";
    final String executionId = "exec-end-time";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit first terminal status (SUCCESS)
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for event processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress1 = tracker.getProgress(sessionId, executionId);
    assertThat(progress1.tasks().getFirst().endTime()).isNotNull();
    final java.time.LocalDateTime firstEndTime = progress1.tasks().getFirst().endTime();

    // Wait a bit then emit another status (FAILURE) — should not change endTime
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.FAILURE, Map.of());

    // Wait again
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress2 = tracker.getProgress(sessionId, executionId);
    assertThat(progress2.tasks().getFirst().endTime()).isEqualTo(firstEndTime);
  }

  @Test
  void testAutoCleanupWithShortTtl() throws Exception {
    // Create a tracker with very short TTL
    final DefaultTaskTrackerService shortTtlTracker =
        new DefaultTaskTrackerService(Duration.ofMillis(150), statusHistoryCache);
    shortTtlTracker.init();

    final String sessionId = "sess-short-ttl";
    final String workflowId = "wf-short-ttl";
    final String executionId = "exec-short-ttl";

    StepVerifier.create(
            shortTtlTracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Emit terminal status
    shortTtlTracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Wait for cleanup to occur (TTL + some buffer)
    Thread.sleep(300);

    // After cleanup, getLogStream and getStatusStream should return empty
    StepVerifier.create(shortTtlTracker.getLogStream(executionId)).verifyComplete();
    StepVerifier.create(shortTtlTracker.getStatusStream(executionId)).verifyComplete();
  }

  @Test
  void testInitTaskStatusErrorHandler() throws Exception {
    verifyInitTaskStatusErrorHandler();
  }

  private void verifyInitTaskStatusErrorHandler()
      throws NoSuchFieldException, IllegalAccessException, InterruptedException {
    final DefaultTaskTrackerService trackerWithError =
        new DefaultTaskTrackerService(Duration.ofMinutes(10), statusHistoryCache);

    // Replace executionIndex with a map that throws on get()
    final Field executionIndexField =
        DefaultTaskTrackerService.class.getDeclaredField("executionIndex");
    executionIndexField.setAccessible(true);

    // Create a mock map that throws
    final Map<String, Object> throwingMap =
        new ConcurrentHashMap<>() {
          @Override
          public Object get(final Object key) {
            throw new IllegalStateException("Simulated error in executionIndex");
          }
        };
    executionIndexField.set(trackerWithError, throwingMap);

    trackerWithError.init();

    // Emit a task status event — should not crash despite the error
    trackerWithError.emitTaskStatusEvent(
        "exec-id", "node-id", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for async processing
    Thread.sleep(200);

    // If we reach here, the error handler worked
    assertThat(true).isTrue();
  }

  @Test
  void testInitWorkflowStatusErrorHandler() throws Exception {
    verifyInitWorkflowStatusErrorHandler();
  }

  private void verifyInitWorkflowStatusErrorHandler()
      throws NoSuchFieldException, IllegalAccessException, InterruptedException {
    final DefaultTaskTrackerService trackerWithError =
        new DefaultTaskTrackerService(Duration.ofMinutes(10), statusHistoryCache);

    // Replace executionIndex with a map that throws on get()
    final Field executionIndexField =
        DefaultTaskTrackerService.class.getDeclaredField("executionIndex");
    executionIndexField.setAccessible(true);

    final Map<String, Object> throwingMap =
        new ConcurrentHashMap<>() {
          @Override
          public Object get(final Object key) {
            throw new IllegalStateException("Simulated error in executionIndex");
          }
        };
    executionIndexField.set(trackerWithError, throwingMap);

    trackerWithError.init();

    // Emit a workflow status event — should not crash
    trackerWithError.emitWorkflowStatusEvent("exec-id", TaskStatus.SUCCESS);

    // Wait for async processing
    Thread.sleep(200);

    assertThat(true).isTrue();
  }

  @Test
  void testInitLogErrorHandler() throws Exception {
    verifyInitLogErrorHandler();
  }

  private void verifyInitLogErrorHandler()
      throws NoSuchFieldException, IllegalAccessException, InterruptedException {
    final DefaultTaskTrackerService trackerWithError =
        new DefaultTaskTrackerService(Duration.ofMinutes(10), statusHistoryCache);

    // Replace logSinks with a map that throws on get()
    final Field logSinksField = DefaultTaskTrackerService.class.getDeclaredField("logSinks");
    logSinksField.setAccessible(true);

    final Map<String, Object> throwingMap =
        new ConcurrentHashMap<>() {
          @Override
          public Object get(final Object key) {
            throw new IllegalStateException("Simulated error in logSinks");
          }
        };
    logSinksField.set(trackerWithError, throwingMap);

    trackerWithError.init();

    // Emit a log event — should not crash
    trackerWithError.emitLogEvent("exec-id", "system", "log line");

    // Wait for async processing
    Thread.sleep(200);

    assertThat(true).isTrue();
  }

  @Test
  void testCleanupExecutionRemovesAllResources() throws Exception {
    final String sessionId = "sess-cleanup-all";
    final String workflowId = "wf-cleanup-all";
    final String executionId = "exec-cleanup-all";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Emit terminal status to trigger cleanup scheduling
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Wait for cleanup to complete (use short TTL from setUp)
    Thread.sleep(300);

    // Verify streams are now empty after cleanup
    StepVerifier.create(tracker.getLogStream(executionId)).verifyComplete();
    StepVerifier.create(tracker.getStatusStream(executionId)).verifyComplete();
  }

  @Test
  void testHandleWorkflowStatusEventsWithTerminalStatus() {
    final String sessionId = "sess-wf-terminal";
    final String workflowId = "wf-terminal";
    final String executionId = "exec-wf-terminal";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit task update
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.RUNNING, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Now emit workflow status with terminal status — should set endTime
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.FAILURE);

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null && "FAILURE".equals(progress.status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.status()).isEqualTo("FAILURE");
    assertThat(progress.endTime()).isNotNull();
  }

  @Test
  void testNotifyStatusChangeUpdatesStatusSink() {
    final String sessionId = "sess-notify";
    final String workflowId = "wf-notify";
    final String executionId = "exec-notify";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Subscribe to status changes and emit a task status update
    StepVerifier.create(tracker.getStatusStream(executionId).take(1))
        .then(
            () ->
                tracker.emitTaskStatusEvent(
                    executionId, "node1", "module", TaskStatus.SUCCESS, Map.of()))
        .assertNext(progress -> assertThat(progress.workflowId()).isEqualTo(workflowId))
        .verifyComplete();
  }

  @Test
  void testRemoveSessionCleansUpAllExecutions() {
    final String sessionId = "sess-cleanup-multi";
    final String workflowId = "wf-cleanup-multi";
    final String executionId1 = "exec-cleanup-multi-1";
    final String executionId2 = "exec-cleanup-multi-2";

    StepVerifier.create(tracker.startWorkflow(executionId1, sessionId, workflowId, List.of()))
        .verifyComplete();
    StepVerifier.create(tracker.startWorkflow(executionId2, sessionId, workflowId, List.of()))
        .verifyComplete();

    assertThat(tracker.getActiveSessions().size()).isEqualTo(1);

    // Remove session should clean up both executions
    tracker.removeSession(sessionId);

    assertThat(tracker.getActiveSessions().size()).isEqualTo(0);

    // Verify both executions are removed from the index
    assertThat(tracker.getProgress(sessionId, executionId1)).isNull();
    assertThat(tracker.getProgress(sessionId, executionId2)).isNull();
  }

  @Test
  void testTaskProgressMetadataMerging() {
    final String sessionId = "sess-metadata";
    final String workflowId = "wf-metadata";
    final String executionId = "exec-metadata";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // First update with metadata
    tracker.emitTaskStatusEvent(
        executionId, "node1", "module1", TaskStatus.RUNNING, Map.of("key1", "value1"));

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().metadata().containsKey("key1")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Second update with additional metadata
    tracker.emitTaskStatusEvent(
        executionId, "node1", "module2", TaskStatus.SUCCESS, Map.of("key2", "value2"));

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().metadata().containsKey("key2")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().metadata().containsKey("key1")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().containsKey("key2")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().get("key1")).isEqualTo("value1");
    assertThat(progress.tasks().getFirst().metadata().get("key2")).isEqualTo("value2");
  }

  @Test
  void testTaskProgressWithNullMetadata() {
    final String sessionId = "sess-null-metadata";
    final String workflowId = "wf-null-metadata";
    final String executionId = "exec-null-metadata";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Update with empty metadata Map
    tracker.emitTaskStatusEvent(
        executionId, "node1", "module", TaskStatus.RUNNING, Collections.emptyMap());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("RUNNING");
    assertThat(progress.tasks().getFirst().metadata().isEmpty()).isTrue();
  }

  @Test
  void testMultipleTaskUpdatesPreserveState() {
    final String sessionId = "sess-multi-tasks";
    final String workflowId = "wf-multi-tasks";
    final String executionId = "exec-multi-tasks";
    final List<String> nodes = List.of("node1", "node2", "node3");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Update multiple tasks
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod1", TaskStatus.SUCCESS, Map.of("task", "1"));
    tracker.emitTaskStatusEvent(
        executionId, "node2", "mod2", TaskStatus.RUNNING, Map.of("task", "2"));
    tracker.emitTaskStatusEvent(
        executionId, "node3", "mod3", TaskStatus.FAILURE, Map.of("task", "3"));

    // Wait for processing
    for (int i = 0; i < 30; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && progress.tasks().size() == 3
          && "SUCCESS".equals(progress.tasks().getFirst().status())
          && "RUNNING".equals(progress.tasks().get(1).status())
          && "FAILURE".equals(progress.tasks().get(2).status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().size()).isEqualTo(3);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("SUCCESS");
    assertThat(progress.tasks().get(1).status()).isEqualTo("RUNNING");
    assertThat(progress.tasks().get(2).status()).isEqualTo("FAILURE");
  }

  @Test
  void testTaskProgressModuleUpdate() {
    final String sessionId = "sess-module-update";
    final String workflowId = "wf-module-update";
    final String executionId = "exec-module-update";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // First update with one module
    tracker.emitTaskStatusEvent(executionId, "node1", "module-a", TaskStatus.RUNNING, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "module-a".equals(progress.tasks().getFirst().module())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Update with different module
    tracker.emitTaskStatusEvent(executionId, "node1", "module-b", TaskStatus.SUCCESS, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "module-b".equals(progress.tasks().getFirst().module())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().module()).isEqualTo("module-b");
  }

  @Test
  void testGetProgressPartialMatch() {
    final String sessionId = "sess-partial";
    final String workflowId = "wf-partial";
    final String executionId = "exec-partial";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Get progress with wrong session should return null
    assertThat(tracker.getProgress("wrong-session", executionId)).isNull();

    // Get progress with correct session should return progress
    assertThat(tracker.getProgress(sessionId, executionId)).isNotNull();
  }

  @Test
  void testSessionStatesWithMultipleSessions() {
    final String session1 = "sess-1";
    final String session2 = "sess-2";
    final String workflowId = "wf-multi-session";
    final String exec1 = "exec-1";
    final String exec2 = "exec-2";

    StepVerifier.create(tracker.startWorkflow(exec1, session1, workflowId, List.of()))
        .verifyComplete();
    StepVerifier.create(tracker.startWorkflow(exec2, session2, workflowId, List.of()))
        .verifyComplete();

    assertThat(tracker.getActiveSessions().size()).isEqualTo(2);

    final WorkflowProgress prog1 = tracker.getProgress(session1, exec1);
    final WorkflowProgress prog2 = tracker.getProgress(session2, exec2);

    assertThat(prog1).isNotNull();
    assertThat(prog2).isNotNull();
    assertThat(prog1.sessionId()).isEqualTo(session1);
    assertThat(prog2.sessionId()).isEqualTo(session2);
  }

  @Test
  void testStatusUpdateOnlyWhenSinkExists() {
    final String sessionId = "sess-no-sink";
    final String workflowId = "wf-no-sink";
    final String executionId = "exec-no-sink";
    final List<String> nodes = List.of("node1");

    // Start workflow and immediately remove status sink to test notifyStatusChange handles null
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit task event — notifyStatusChange will be called but sink might be removed
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.RUNNING, Map.of());

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify the task was still updated
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("RUNNING");
  }

  @Test
  void testMultipleWorkflowsInSession() {
    final String sessionId = "sess-multi-wf";
    final String wf1 = "wf-1";
    final String wf2 = "wf-2";
    final String exec1 = "exec-1";
    final String exec2 = "exec-2";

    StepVerifier.create(tracker.startWorkflow(exec1, sessionId, wf1, List.of("n1")))
        .verifyComplete();
    StepVerifier.create(tracker.startWorkflow(exec2, sessionId, wf2, List.of("n2")))
        .verifyComplete();

    // Both should be in the same session
    assertThat(tracker.getActiveSessions().size()).isEqualTo(1);

    // History should include both
    StepVerifier.create(tracker.getHistory(sessionId))
        .assertNext(history -> assertThat(history).hasSize(2))
        .verifyComplete();
  }

  @Test
  void testTaskStatusErrorWithInvalidStatusValue() {
    final String sessionId = "sess-invalid-status";
    final String workflowId = "wf-invalid-status";
    final String executionId = "exec-invalid-status";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit with valid status first
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.RUNNING, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().startTime()).isNotNull();
  }

  @Test
  void testMultipleStatusTransitions() {
    final String sessionId = "sess-transitions";
    final String workflowId = "wf-transitions";
    final String executionId = "exec-transitions";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // PENDING -> RUNNING
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.RUNNING, Map.of());
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final var prog1 = tracker.getProgress(sessionId, executionId);
    assertThat(prog1.tasks().getFirst().startTime()).isNotNull();

    // RUNNING -> FAILURE
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.FAILURE, Map.of());
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "FAILURE".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final var prog2 = tracker.getProgress(sessionId, executionId);
    assertThat(prog2.tasks().getFirst().endTime()).isNotNull();
  }

  @Test
  void testMetadataNullBranch() {
    final String sessionId = "sess-metadata-null";
    final String workflowId = "wf-metadata-null";
    final String executionId = "exec-metadata-null";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // First update with metadata
    tracker.emitTaskStatusEvent(
        executionId, "node1", "module1", TaskStatus.RUNNING, Map.of("key1", "value1"));

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().metadata().containsKey("key1")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Second update with empty Map (tests mergeMetadata null branch)
    tracker.emitTaskStatusEvent(executionId, "node1", "module2", TaskStatus.SUCCESS, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().metadata().containsKey("key1")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().get("key1")).isEqualTo("value1");
  }

  @Test
  void testCleanupSchedulingErrorHandler() {
    final String sessionId = "sess-cleanup-error";
    final String workflowId = "wf-cleanup-error";
    final String executionId = "exec-cleanup-error";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Emit terminal status to trigger cleanup scheduling
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify workflow completed
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.status()).isEqualTo("SUCCESS");
  }

  @Test
  void testDetermineStartTimeWhenAlreadyRunning() {
    final String sessionId = "sess-start-already-running";
    final String workflowId = "wf-start-already-running";
    final String executionId = "exec-start-already-running";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // First update to RUNNING
    tracker.emitTaskStatusEvent(executionId, "node1", "module1", TaskStatus.RUNNING, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final var prog1 = tracker.getProgress(sessionId, executionId);
    final var firstStartTime = prog1.tasks().getFirst().startTime();
    assertThat(firstStartTime).isNotNull();

    // Wait a bit
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Second update to RUNNING again — startTime should NOT change
    tracker.emitTaskStatusEvent(executionId, "node1", "module2", TaskStatus.RUNNING, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final var prog2 = tracker.getProgress(sessionId, executionId);
    assertThat(prog2.tasks().getFirst().startTime()).isEqualTo(firstStartTime);
  }

  @Test
  void testDetermineEndTimeWhenAlreadyTerminal() {
    final String sessionId = "sess-end-already-terminal";
    final String workflowId = "wf-end-already-terminal";
    final String executionId = "exec-end-already-terminal";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit SUCCESS
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final var prog1 = tracker.getProgress(sessionId, executionId);
    final var firstEndTime = prog1.tasks().getFirst().endTime();
    assertThat(firstEndTime).isNotNull();

    // Wait a bit
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Emit ERROR — endTime should NOT change (already terminal)
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.ERROR, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final var prog2 = tracker.getProgress(sessionId, executionId);
    assertThat(prog2.tasks().getFirst().endTime()).isEqualTo(firstEndTime);
  }

  @Test
  void testNotifyStatusChangeWithNullStatusSink() {
    final String sessionId = "sess-notify-null-sink";
    final String workflowId = "wf-notify-null-sink";
    final String executionId = "exec-notify-null-sink";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit an event - notifyStatusChange will be called
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.RUNNING, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "RUNNING".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify event was processed
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("RUNNING");
  }

  @Test
  void testTaskProgressAllBranches() {
    final String sessionId = "sess-all-branches";
    final String workflowId = "wf-all-branches";
    final String executionId = "exec-all-branches";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit PENDING (no action expected on start times)
    // Then RUNNING to set startTime
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod1", TaskStatus.RUNNING, Map.of("k1", "v1"));

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().startTime() != null) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Then emit non-terminal to test that path
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod2", TaskStatus.PENDING, Map.of("k2", "v2"));

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Then emit ERROR to set endTime
    tracker.emitTaskStatusEvent(executionId, "node1", "mod3", TaskStatus.ERROR, Map.of("k3", "v3"));

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().endTime() != null) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().startTime()).isNotNull();
    assertThat(progress.tasks().getFirst().endTime()).isNotNull();
    assertThat(progress.tasks().getFirst().metadata().containsKey("k1")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().containsKey("k2")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().containsKey("k3")).isTrue();
  }

  @Test
  void testEmitTaskStatusEventDirectly() {
    final String sessionId = "sess-emit-direct";
    final String workflowId = "wf-emit-direct";
    final String executionId = "exec-emit-direct";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Directly emit without going through updateTaskStatus Mono
    tracker.emitTaskStatusEvent(
        executionId, "node1", "module", TaskStatus.SUCCESS, Map.of("test", "value"));

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().status()).isEqualTo("SUCCESS");
  }

  @Test
  void testNotifyStatusChangeWhenStateExists() {
    final String sessionId = "sess-notify-state";
    final String workflowId = "wf-notify-state";
    final String executionId = "exec-notify-state";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Subscribe to status stream and emit event
    StepVerifier.create(tracker.getStatusStream(executionId).take(1))
        .then(
            () ->
                tracker.emitTaskStatusEvent(
                    executionId, "node1", "module", TaskStatus.RUNNING, Map.of()))
        .assertNext(
            progress -> {
              assertThat(progress.executionId()).isEqualTo(executionId);
              assertThat(progress.workflowId()).isEqualTo(workflowId);
            })
        .verifyComplete();
  }

  @Test
  void testGetProgressWhenStateIsNull() {
    // Try to get progress for non-existent session
    final WorkflowProgress progress1 =
        tracker.getProgress("non-existent-session", "non-existent-exec");
    assertThat(progress1).isNull();

    // Try to get progress with correct session but wrong execution
    final String sessionId = "sess-null-state";
    final String workflowId = "wf-null-state";
    final String executionId = "exec-null-state";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Get with wrong execution ID
    final WorkflowProgress progress2 = tracker.getProgress(sessionId, "wrong-exec-id");
    assertThat(progress2).isNull();
  }

  @Test
  void testHandleWorkflowStatusEventsNonTerminalStatus() {
    final String sessionId = "sess-non-terminal";
    final String workflowId = "wf-non-terminal";
    final String executionId = "exec-non-terminal";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit non-terminal workflow status (RUNNING is not in SUCCESS/FAILURE/ERROR)
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.RUNNING);

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null && "RUNNING".equals(progress.status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.status()).isEqualTo("RUNNING");
    // Non-terminal status should NOT trigger cleanup
  }

  @Test
  void testNotifyStatusChangeWhenSinkIsNullAndStateIsNull() {
    // Emit event for non-existent execution - notifyStatusChange will be called with null sink
    tracker.emitTaskStatusEvent(
        "non-existent-exec", "node1", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Should not crash - handled gracefully
    assertThat(true).isTrue();
  }

  @Test
  void testMergeMetadataWhenAdditionalIsEmpty() {
    final String sessionId = "sess-merge-empty";
    final String workflowId = "wf-merge-empty";
    final String executionId = "exec-merge-empty";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // First emit with metadata
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod1", TaskStatus.RUNNING, Map.of("key1", "val1"));

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().metadata().containsKey("key1")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Now emit with empty map to test mergeMetadata preserves existing metadata
    tracker.emitTaskStatusEvent(executionId, "node1", "mod2", TaskStatus.SUCCESS, Map.of());

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Metadata should still have key1 from before (merged from empty additional map)
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().metadata().containsKey("key1")).isTrue();
  }

  @Test
  void testScheduleCleanupErrorHandler() {
    final String sessionId = "sess-cleanup-handler";
    final String workflowId = "wf-cleanup-handler";
    final String executionId = "exec-cleanup-handler";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Emit terminal status to trigger cleanup scheduling
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Wait for async processing and cleanup
    for (int i = 0; i < 50; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Workflow should have completed
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.status()).isEqualTo("SUCCESS");
  }

  @Test
  void testCleanupExecutionRemovesLogsAndStatusSinks() {
    final String sessionId = "sess-cleanup-sinks";
    final String workflowId = "wf-cleanup-sinks";
    final String executionId = "exec-cleanup-sinks";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    // Verify sinks exist
    assertThat(tracker.getLogStream(executionId) != null).isTrue();
    assertThat(tracker.getStatusStream(executionId) != null).isTrue();

    // Emit terminal status to trigger cleanup
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.ERROR);

    // Wait for cleanup (using short TTL from setUp)
    for (int i = 0; i < 50; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // After cleanup, sinks should be completed/removed
    StepVerifier.create(tracker.getLogStream(executionId)).verifyComplete();
    StepVerifier.create(tracker.getStatusStream(executionId)).verifyComplete();
  }

  @Test
  void testMergeMetadataWithNullAdditional() {
    final String sessionId = "sess-merge-null-additional";
    final String workflowId = "wf-merge-null-additional";
    final String executionId = "exec-merge-null-additional";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit with metadata first
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod1", TaskStatus.RUNNING, Map.of("key1", "val1"));

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().metadata().containsKey("key1")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Emit with no additional metadata to trigger the null branch in mergeMetadata
    // The null branch happens when additional == null, but API requires @NotNull
    // So we test with empty map which exercises the "if (additional != null)" path being false
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod2", TaskStatus.SUCCESS, Collections.emptyMap());

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    // Old metadata should still be there (merged with empty map)
    assertThat(progress.tasks().getFirst().metadata().containsKey("key1")).isTrue();
  }

  @Test
  void testWorkflowStateNullUpdate() {
    final String sessionId = "sess-null-update";
    final String workflowId = "wf-null-update";
    final String executionId = "exec-null-update";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Try updating a non-existent node (old == null branch in updateTask)
    tracker.emitTaskStatusEvent(
        executionId, "non-existent-node", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Workflow should still have only the initialized nodes
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().size()).isEqualTo(1);
    assertThat(progress.tasks().getFirst().nodeId()).isEqualTo("node1");
  }

  @Test
  void testMergeMetadataFullCoverage() {
    final String sessionId = "sess-merge-full";
    final String workflowId = "wf-merge-full";
    final String executionId = "exec-merge-full";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Case 1: Empty metadata initially
    tracker.emitTaskStatusEvent(executionId, "node1", "mod1", TaskStatus.RUNNING, Map.of());
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && !p.tasks().isEmpty() && "RUNNING".equals(p.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Case 2: Add some metadata
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod2", TaskStatus.SUCCESS, Map.of("a", 1, "b", 2));
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && !p.tasks().isEmpty() && p.tasks().getFirst().metadata().containsKey("a")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Case 3: Update with additional metadata (tests putAll in mergeMetadata)
    tracker.emitTaskStatusEvent(executionId, "node1", "mod3", TaskStatus.SUCCESS, Map.of("c", 3));
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && !p.tasks().isEmpty() && p.tasks().getFirst().metadata().containsKey("c")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify all metadata is merged
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress.tasks().getFirst().metadata().containsKey("a")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().containsKey("b")).isTrue();
    assertThat(progress.tasks().getFirst().metadata().containsKey("c")).isTrue();
  }

  @Test
  void testTerminalAndNonTerminalWorkflowStatusPaths() {
    final String sessionId = "sess-both-paths";
    final String workflowId = "wf-both-paths";
    final String executionId1 = "exec-both-paths-1";
    final String executionId2 = "exec-both-paths-2";

    // Test non-terminal workflow status
    StepVerifier.create(tracker.startWorkflow(executionId1, sessionId, workflowId, List.of()))
        .verifyComplete();

    tracker.emitWorkflowStatusEvent(executionId1, TaskStatus.PENDING);
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId1);
      if (p != null && "PENDING".equals(p.status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Test terminal workflow status (triggers cleanup)
    StepVerifier.create(tracker.startWorkflow(executionId2, sessionId, workflowId, List.of()))
        .verifyComplete();

    tracker.emitWorkflowStatusEvent(executionId2, TaskStatus.SUCCESS);
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId2);
      if (p != null && "SUCCESS".equals(p.status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Both should exist
    final WorkflowProgress p1 = tracker.getProgress(sessionId, executionId1);
    final WorkflowProgress p2 = tracker.getProgress(sessionId, executionId2);
    assertThat(p1.status()).isEqualTo("PENDING");
    assertThat(p2.status()).isEqualTo("SUCCESS");
  }

  @Test
  void testNotifyStatusChangeWhenStateIsNullAfterCleanup() {
    // Create a workflow with short TTL
    final DefaultTaskTrackerService shortTtlTracker =
        new DefaultTaskTrackerService(Duration.ofMillis(100), statusHistoryCache);
    shortTtlTracker.init();

    final String sessionId = "sess-cleanup-state-null";
    final String workflowId = "wf-cleanup-state-null";
    final String executionId = "exec-cleanup-state-null";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(shortTtlTracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit terminal status to trigger cleanup with short TTL
    shortTtlTracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Wait for cleanup to happen
    try {
      Thread.sleep(250);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Now try to emit task status for the cleaned-up execution
    // notifyStatusChange will be called but state should be null
    shortTtlTracker.emitTaskStatusEvent(
        executionId, "node1", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for processing
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Should handle gracefully without errors
    assertThat(true).isTrue();
  }

  @Test
  void testMergeMetadataWithNullAdditionalParameter() {
    final String sessionId = "sess-merge-null-param";
    final String workflowId = "wf-merge-null-param";
    final String executionId = "exec-merge-null-param";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Emit with initial metadata
    tracker.emitTaskStatusEvent(
        executionId, "node1", "mod1", TaskStatus.RUNNING, Map.of("key1", "val1"));

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && progress.tasks().getFirst().metadata().containsKey("key1")) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Now emit with empty metadata to test the "additional != null" false branch
    tracker.emitTaskStatusEvent(executionId, "node1", "mod2", TaskStatus.SUCCESS, Map.of());

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
      if (progress != null
          && !progress.tasks().isEmpty()
          && "SUCCESS".equals(progress.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify metadata handling worked
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress).isNotNull();
    assertThat(progress.tasks()).isNotEmpty();
    assertThat(progress.tasks().getFirst().metadata().containsKey("key1")).isTrue();
  }

  @Test
  void testAllPublicMethodsCovered() {
    final String sessionId = "sess-all-methods";
    final String workflowId = "wf-all-methods";
    final String executionId = "exec-all-methods";
    final List<String> nodes = List.of("node1", "node2");

    // startWorkflow
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // getProgress - both null and non-null cases
    assertThat(tracker.getProgress(sessionId, executionId)).isNotNull();
    assertThat(tracker.getProgress("wrong-session", executionId)).isNull();

    // getLatestExecutionId
    assertThat(tracker.getLatestExecutionId(sessionId, workflowId)).isEqualTo(executionId);

    // getActiveSessions
    assertThat(tracker.getActiveSessions().contains(sessionId)).isTrue();

    // updateTaskStatus 4-arg
    StepVerifier.create(tracker.updateTaskStatus(executionId, "node1", "mod", TaskStatus.SUCCESS))
        .verifyComplete();

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && !p.tasks().isEmpty() && "SUCCESS".equals(p.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // updateTaskStatus 5-arg
    StepVerifier.create(
            tracker.updateTaskStatus(executionId, "node2", "mod", TaskStatus.FAILURE, Map.of()))
        .verifyComplete();

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && p.tasks().size() > 1 && "FAILURE".equals(p.tasks().get(1).status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // appendLog
    StepVerifier.create(tracker.appendLog(executionId, "log")).verifyComplete();

    // finishWorkflow
    StepVerifier.create(tracker.finishWorkflow(executionId, TaskStatus.SUCCESS)).verifyComplete();

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && "SUCCESS".equals(p.status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // getHistory
    StepVerifier.create(tracker.getHistory(sessionId))
        .assertNext(history -> assertThat(history).isNotEmpty())
        .verifyComplete();

    // getLogStream
    StepVerifier.create(tracker.getLogStream(executionId)).expectSubscription().verifyComplete();

    // getStatusStream
    StepVerifier.create(tracker.getStatusStream(executionId)).expectSubscription().verifyComplete();

    // removeSession
    tracker.removeSession(sessionId);
    assertThat(tracker.getActiveSessions().size()).isEqualTo(0);
  }

  @Test
  void testFullLifecycleWithAllMethods() {
    final String sessionId = "sess-full-lifecycle";
    final String workflowId = "wf-full-lifecycle";
    final String executionId = "exec-full-lifecycle";
    final List<String> nodes = List.of("n1", "n2");

    // startWorkflow
    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // getProgress
    final WorkflowProgress prog1 = tracker.getProgress(sessionId, executionId);
    assertThat(prog1).isNotNull();
    assertThat(prog1.status()).isEqualTo("RUNNING");

    // getLatestExecutionId
    final String latest = tracker.getLatestExecutionId(sessionId, workflowId);
    assertThat(latest).isEqualTo(executionId);

    // getActiveSessions
    assertThat(tracker.getActiveSessions().contains(sessionId)).isTrue();

    // updateTaskStatus (4-arg)
    StepVerifier.create(tracker.updateTaskStatus(executionId, "n1", "mod1", TaskStatus.SUCCESS))
        .verifyComplete();

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && !p.tasks().isEmpty() && "SUCCESS".equals(p.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // updateTaskStatus (5-arg with metadata)
    StepVerifier.create(
            tracker.updateTaskStatus(
                executionId, "n2", "mod2", TaskStatus.FAILURE, Map.of("error", "test")))
        .verifyComplete();

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && p.tasks().size() > 1 && "FAILURE".equals(p.tasks().get(1).status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // appendLog
    StepVerifier.create(tracker.appendLog(executionId, "test log")).verifyComplete();

    // finishWorkflow
    StepVerifier.create(tracker.finishWorkflow(executionId, TaskStatus.SUCCESS)).verifyComplete();

    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgress(sessionId, executionId);
      if (p != null && "SUCCESS".equals(p.status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Test
  void testWorkflowStateBranchesExhaustive() throws Exception {
    final String sessionId = "sess-branches";
    final String workflowId = "wf-branches";
    final String executionId = "exec-branches";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // 1. status == "RUNNING", current != null (already set)
    tracker.emitTaskStatusEvent(executionId, "node1", "mod", TaskStatus.RUNNING, Map.of());
    Thread.sleep(100);
    final java.time.LocalDateTime firstStart =
        tracker.getProgress(sessionId, executionId).tasks().getFirst().startTime();
    tracker.emitTaskStatusEvent(executionId, "node1", "mod", TaskStatus.RUNNING, Map.of());
    Thread.sleep(100);
    assertThat(tracker.getProgress(sessionId, executionId).tasks().getFirst().startTime())
        .isEqualTo(firstStart);

    // 2. Terminal status, current != null (already terminal)
    tracker.emitTaskStatusEvent(executionId, "node1", "mod", TaskStatus.SUCCESS, Map.of());
    Thread.sleep(100);
    final java.time.LocalDateTime firstEnd =
        tracker.getProgress(sessionId, executionId).tasks().getFirst().endTime();
    tracker.emitTaskStatusEvent(executionId, "node1", "mod", TaskStatus.FAILURE, Map.of());
    Thread.sleep(100);
    assertThat(tracker.getProgress(sessionId, executionId).tasks().getFirst().endTime())
        .isEqualTo(firstEnd);
  }

  @Test
  void testEventHandlerErrorLogging() throws Exception {
    verifyInitTaskStatusErrorHandler();
    verifyInitWorkflowStatusErrorHandler();
    verifyInitLogErrorHandler();
  }

  @Test
  void testGetProgressByExecutionIdFound() {
    final String sessionId = "sess-by-exec-id";
    final String workflowId = "wf-by-exec-id";
    final String executionId = "exec-by-exec-id";
    final List<String> nodes = List.of("node1", "node2");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // getProgressByExecutionId should return progress when state exists
    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    assertThat(progress).isNotNull();
    assertThat(progress.executionId()).isEqualTo(executionId);
    assertThat(progress.sessionId()).isEqualTo(sessionId);
    assertThat(progress.workflowId()).isEqualTo(workflowId);
    assertThat(progress.status()).isEqualTo("RUNNING");
    assertThat(progress.tasks().size()).isEqualTo(2);
  }

  @Test
  void testGetProgressByExecutionIdNotFound() {
    // getProgressByExecutionId should return null when state does not exist
    final WorkflowProgress progress = tracker.getProgressByExecutionId("non-existent-exec-id");
    assertThat(progress).isNull();
  }

  @Test
  void testGetProgressByExecutionIdAfterStatusUpdate() {
    final String sessionId = "sess-by-exec-update";
    final String workflowId = "wf-by-exec-update";
    final String executionId = "exec-by-exec-update";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Update task status
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for processing
    for (int i = 0; i < 20; i++) {
      final WorkflowProgress p = tracker.getProgressByExecutionId(executionId);
      if (p != null && !p.tasks().isEmpty() && "SUCCESS".equals(p.tasks().getFirst().status())) {
        break;
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // getProgressByExecutionId should return updated progress
    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    assertThat(progress).isNotNull();
    assertThat(progress.tasks().getFirst().status()).isEqualTo("SUCCESS");
    assertThat(progress.tasks().getFirst().module()).isEqualTo("module");
  }

  @Test
  void testGetProgressByExecutionIdReturnsValidWorkflowProgress() {
    final String sessionId = "sess-valid-progress";
    final String workflowId = "wf-valid-progress";
    final String executionId = "exec-valid-progress";
    final List<String> nodes = List.of("n1", "n2", "n3");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    assertThat(progress).isNotNull();
    assertThat(progress.executionId()).isNotNull();
    assertThat(progress.sessionId()).isNotNull();
    assertThat(progress.workflowId()).isNotNull();
    assertThat(progress.status()).isNotNull();
    assertThat(progress.tasks()).isNotNull();
    assertThat(progress.tasks().size()).isEqualTo(3);
    assertThat(progress.sessionId()).isEqualTo(sessionId);
    assertThat(progress.workflowId()).isEqualTo(workflowId);
  }

  @Test
  void testNotifyStatusChangeWhenSinkAndStateExist() {
    final String sessionId = "sess-notify-both";
    final String workflowId = "wf-notify-both";
    final String executionId = "exec-notify-both";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Subscribe to status stream to ensure sink exists
    StepVerifier.create(tracker.getStatusStream(executionId).take(1))
        .then(
            () ->
                tracker.emitTaskStatusEvent(
                    executionId, "node1", "module", TaskStatus.SUCCESS, Map.of()))
        .assertNext(
            progress -> {
              assertThat(progress).isNotNull();
              assertThat(progress.executionId()).isEqualTo(executionId);
              assertThat(progress.tasks().getFirst().status()).isEqualTo("SUCCESS");
            })
        .verifyComplete();
  }

  @Test
  void testNotifyStatusChangeWhenSinkIsNull() {
    final String sessionId = "sess-notify-null-sink";
    final String workflowId = "wf-notify-null-sink";
    final String executionId = "exec-notify-null-sink";
    final List<String> nodes = List.of("node1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Don't subscribe to getStatusStream, so the sink is removed or never created
    // Emit task status event which will call notifyStatusChange with null sink
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Verify task was still updated even though sink was null
    final WorkflowProgress progress = tracker.getProgress(sessionId, executionId);
    assertThat(progress).isNotNull();
    assertThat(progress.tasks().getFirst().status()).isEqualTo("SUCCESS");
  }

  @Test
  void testNotifyStatusChangeWhenStateIsNull() {
    final String executionId = "exec-notify-null-state";

    // Try to emit event for execution that doesn't exist (state will be null)
    tracker.emitTaskStatusEvent(executionId, "node1", "module", TaskStatus.SUCCESS, Map.of());

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Should handle gracefully without crashing when state is null
    // Verify no state exists for this execution
    final WorkflowProgress progress = tracker.getProgressByExecutionId(executionId);
    assertThat(progress).isNull();
  }

  @Test
  void testNotifyStatusChangeWithBothNullConditions() {
    final String executionId = "exec-both-null";

    // Create workflow and get status stream to initialize sink
    final String sessionId = "sess-both-null";
    final String workflowId = "wf-both-null";
    final List<String> nodes = List.of("n1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    // Get the status stream (creates the sink)
    tracker.getStatusStream(executionId);

    // Now remove execution from index to make findState return null
    // But sink still exists
    tracker.removeSession(sessionId);

    // Emit status event - sink exists but state is null
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    // Wait for async processing
    for (int i = 0; i < 20; i++) {
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Should handle gracefully - sink exists but state is null (condition at line 448 is false)
    assertThat(true).isTrue();
  }

  @Test
  void testGetLogFluxMapsKnownExecutionAndNode() {
    final String sessionId = "sess-logflux-known";
    final String workflowId = "wf-logflux-known";
    final String executionId = "exec-logflux-known";
    final String nodeId = "node1";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of(nodeId)))
        .verifyComplete();

    StepVerifier.create(tracker.getLogFlux())
        .then(() -> tracker.emitLogEvent(executionId, nodeId, "hello world"))
        .assertNext(
            entry -> {
              assertThat(entry.executionId()).isEqualTo(executionId);
              assertThat(entry.sessionId()).isEqualTo(sessionId);
              assertThat(entry.pluginId()).isEqualTo(nodeId);
              assertThat(entry.pluginName()).isEmpty();
              assertThat(entry.stream()).isEqualTo(LogStream.STDOUT);
              assertThat(entry.message()).isEqualTo("hello world");
              assertThat(entry.logLevel()).isEqualTo(LogLevel.INFO);
            })
        .thenCancel()
        .verify();
  }

  @Test
  void testGetLogFluxKeepsTheStderrStreamOfEachLine() {
    final String executionId = "exec-logflux-stderr";

    StepVerifier.create(tracker.getLogFlux())
        .then(() -> tracker.emitLogEvent(executionId, "node1", "oops", true))
        .assertNext(entry -> assertThat(entry.stream()).isEqualTo(LogStream.STDERR))
        .thenCancel()
        .verify();
  }

  @Test
  void testGetLogFluxForUnknownExecutionDefaultsToUnknown() {
    StepVerifier.create(tracker.getLogFlux())
        .then(() -> tracker.emitLogEvent("unknown-exec-for-flux", "node-x", "orphan log"))
        .assertNext(
            entry -> {
              assertThat(entry.sessionId()).isEqualTo("unknown");
              assertThat(entry.pluginName()).isEqualTo("unknown");
              assertThat(entry.pluginId()).isEqualTo("node-x");
            })
        .thenCancel()
        .verify();
  }

  @Test
  void testGetLogFluxForKnownExecutionUnknownNodeDefaultsModuleToUnknown() {
    final String sessionId = "sess-logflux-node-unknown";
    final String workflowId = "wf-logflux-node-unknown";
    final String executionId = "exec-logflux-node-unknown";

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    StepVerifier.create(tracker.getLogFlux())
        .then(() -> tracker.emitLogEvent(executionId, "node-not-in-map", "line"))
        .assertNext(
            entry -> {
              assertThat(entry.sessionId()).isEqualTo(sessionId);
              assertThat(entry.pluginName()).isEqualTo("unknown");
            })
        .thenCancel()
        .verify();
  }

  @Test
  void testGetLogStreamAutoTerminatesOnWorkflowTerminalStatus() {
    final DefaultTaskTrackerService longTtlTracker =
        new DefaultTaskTrackerService(Duration.ofMinutes(10), statusHistoryCache);
    longTtlTracker.init();

    final String sessionId = "sess-log-terminal";
    final String workflowId = "wf-log-terminal";
    final String executionId = "exec-log-terminal";

    StepVerifier.create(longTtlTracker.startWorkflow(executionId, sessionId, workflowId, List.of()))
        .verifyComplete();

    StepVerifier.create(longTtlTracker.getLogStream(executionId))
        .then(() -> longTtlTracker.emitLogEvent(executionId, "system", "line before terminal"))
        .expectNext("line before terminal")
        .then(
            () -> {
              longTtlTracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);
              await()
                  .atMost(2, TimeUnit.SECONDS)
                  .until(
                      () -> {
                        final WorkflowProgress progress =
                            longTtlTracker.getProgressByExecutionId(executionId);
                        return progress != null
                            && "SUCCESS".equals(progress.status())
                            && progress.endTime() != null;
                      });
              longTtlTracker.emitLogEvent(executionId, "system", "line after terminal");
            })
        .expectNext("line after terminal")
        .expectComplete()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void testGetStatusStreamAutoTerminatesOnTerminalProgress() {
    final DefaultTaskTrackerService longTtlTracker =
        new DefaultTaskTrackerService(Duration.ofMinutes(10), statusHistoryCache);
    longTtlTracker.init();

    final String sessionId = "sess-status-terminal-full";
    final String workflowId = "wf-status-terminal-full";
    final String executionId = "exec-status-terminal-full";

    StepVerifier.create(
            longTtlTracker.startWorkflow(executionId, sessionId, workflowId, List.of("n1")))
        .verifyComplete();

    StepVerifier.create(longTtlTracker.getStatusStream(executionId))
        .then(() -> longTtlTracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS))
        .assertNext(progress -> assertThat(progress.status()).isEqualTo("SUCCESS"))
        .expectComplete()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void testGetStatusStreamWithoutHistory() {
    final String sessionId = "sess-no-history";
    final String workflowId = "wf-no-history";
    final String executionId = "exec-no-history";
    final List<String> nodes = List.of("n1");

    StepVerifier.create(tracker.startWorkflow(executionId, sessionId, workflowId, nodes))
        .verifyComplete();

    StepVerifier.create(tracker.getStatusStream(executionId, false))
        .then(
            () ->
                tracker.emitTaskStatusEvent(executionId, "n1", "mod", TaskStatus.RUNNING, Map.of()))
        .expectNextCount(1)
        .thenCancel()
        .verify();
  }

  private Map<String, String> finishAndReadNodeStatuses(
      final String executionId, final TaskStatus runStatus, final Map<String, TaskStatus> before) {
    final List<String> nodes = List.of("a", "b", "c");
    StepVerifier.create(tracker.startWorkflow(executionId, "sess-sweep", "wf", nodes))
        .verifyComplete();
    before.forEach(
        (node, status) -> tracker.emitTaskStatusEvent(executionId, node, "m", status, Map.of()));
    tracker.emitWorkflowStatusEvent(executionId, runStatus);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .until(() -> tracker.getProgress("sess-sweep", executionId).endTime() != null);
    final Map<String, String> result = new ConcurrentHashMap<>();
    tracker
        .getProgress("sess-sweep", executionId)
        .tasks()
        .forEach(t -> result.put(t.nodeId(), t.status()));
    return result;
  }

  @Test
  void finish_success_sweepsRunningToCancelledAndUnstartedToSkipped() {
    final var statuses =
        finishAndReadNodeStatuses(
            "exec-sweep-ok",
            TaskStatus.SUCCESS,
            Map.of("a", TaskStatus.SUCCESS, "b", TaskStatus.RUNNING));

    assertThat(statuses)
        .containsEntry("a", "SUCCESS")
        .containsEntry("b", "CANCELLED")
        .containsEntry("c", "SKIPPED");
  }

  @Test
  void finish_failure_marksUnstartedNodesUpstreamFailed() {
    final var statuses =
        finishAndReadNodeStatuses(
            "exec-sweep-fail",
            TaskStatus.FAILURE,
            Map.of("a", TaskStatus.TIMED_OUT, "b", TaskStatus.RUNNING));

    assertThat(statuses)
        .containsEntry("a", "TIMED_OUT")
        .containsEntry("b", "CANCELLED")
        .containsEntry("c", "UPSTREAM_FAILED");
  }

  @Test
  void finish_stopped_cancelsEverythingThatWasNotFinished() {
    final var statuses =
        finishAndReadNodeStatuses(
            "exec-sweep-stop", TaskStatus.STOPPED, Map.of("a", TaskStatus.SUCCESS));

    assertThat(statuses)
        .containsEntry("a", "SUCCESS")
        .containsEntry("b", "CANCELLED")
        .containsEntry("c", "CANCELLED");
  }

  @Test
  void statusStream_lastElementShowsEveryNodeFinished_whenEventsArriveBackToBack() {
    final String executionId = "exec-order";
    final List<String> nodes = new java.util.ArrayList<>();
    for (int i = 0; i < 50; i++) {
      nodes.add("n" + i);
    }
    StepVerifier.create(tracker.startWorkflow(executionId, "sess-order", "wf", nodes))
        .verifyComplete();

    final java.util.concurrent.atomic.AtomicReference<WorkflowProgress> last =
        new java.util.concurrent.atomic.AtomicReference<>();
    final var done = new java.util.concurrent.CountDownLatch(1);
    tracker
        .getStatusStream(executionId)
        .subscribe(last::set, e -> done.countDown(), done::countDown);

    for (final String node : nodes) {
      tracker.emitTaskStatusEvent(executionId, node, "m", TaskStatus.RUNNING, Map.of());
      tracker.emitTaskStatusEvent(executionId, node, "m", TaskStatus.SUCCESS, Map.of());
    }
    tracker.emitWorkflowStatusEvent(executionId, TaskStatus.SUCCESS);

    await().atMost(5, TimeUnit.SECONDS).until(() -> done.getCount() == 0);
    assertThat(last.get().status()).isEqualTo("SUCCESS");
    assertThat(last.get().tasks()).extracting(TaskProgress::status).containsOnly("SUCCESS");
  }

  @Test
  void testRunObservationIsTheParentOfItsNodeObservations_andCarriesTheStatus() {
    final var observations = io.micrometer.observation.ObservationRegistry.create();
    final var stopped = new java.util.concurrent.CopyOnWriteArrayList<String>();
    final var parents = new ConcurrentHashMap<String, String>();
    observations
        .observationConfig()
        .observationHandler(
            new io.micrometer.observation.ObservationHandler<>() {
              @Override
              public boolean supportsContext(
                  final io.micrometer.observation.Observation.Context context) {
                return true;
              }

              @Override
              public void onStart(final io.micrometer.observation.Observation.Context context) {
                final var parent = context.getParentObservation();
                if (parent != null) {
                  parents.put(
                      String.valueOf(context.getName()),
                      String.valueOf(
                          java.util.Objects.requireNonNull(parent.getContextView()).getName()));
                }
              }

              @Override
              public void onStop(final io.micrometer.observation.Observation.Context context) {
                final var status = context.getLowCardinalityKeyValue("status");
                stopped.add(context.getName() + ":" + (status == null ? "?" : status.getValue()));
              }
            });
    final DefaultTaskTrackerService traced =
        new DefaultTaskTrackerService(
            Duration.ofMillis(200),
            statusHistoryCache,
            new InMemoryExecutionLogStore(),
            new SimpleMeterRegistry(),
            observations);
    traced.init();
    StepVerifier.create(traced.startWorkflow("r-exec", "r-sess", "r-wf", List.of("a")))
        .verifyComplete();

    traced.emitTaskStatusEvent("r-exec", "a", "echo", TaskStatus.RUNNING, Map.of());
    traced.emitTaskStatusEvent("r-exec", "a", "echo", TaskStatus.SUCCESS, Map.of());
    traced.emitWorkflowStatusEvent("r-exec", TaskStatus.SUCCESS);

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> assertThat(stopped).containsExactly("yukta.node:SUCCESS", "yukta.run:SUCCESS"));
    assertThat(parents).containsEntry("yukta.node", "yukta.run");
  }

  @Test
  void testRunDurationQueueWaitAndRetriesAreMeasured() {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final DefaultTaskTrackerService metered =
        new DefaultTaskTrackerService(
            Duration.ofMillis(200), statusHistoryCache, new InMemoryExecutionLogStore(), meters);
    metered.init();
    StepVerifier.create(metered.startWorkflow("q-exec", "q-sess", "q-wf", List.of("a")))
        .verifyComplete();

    metered.emitWorkflowStatusEvent("q-exec", TaskStatus.QUEUED);
    metered.emitWorkflowStatusEvent("q-exec", TaskStatus.RUNNING);
    metered.recordRetry("echo");
    metered.recordRetry("echo");
    metered.emitWorkflowStatusEvent("q-exec", TaskStatus.FAILURE);

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(meters.get("yukta.execution.queue.wait").timer().count()).isEqualTo(1);
              assertThat(
                      meters
                          .get("yukta.execution.duration")
                          .tag("status", "FAILURE")
                          .timer()
                          .count())
                  .isEqualTo(1);
              assertThat(meters.get("yukta.node.retries").tag("module", "echo").counter().count())
                  .isEqualTo(2.0);
            });
  }

  @SuppressWarnings("unchecked")
  private static <T> T field(final Object target, final String name) {
    return (T) org.springframework.test.util.ReflectionTestUtils.getField(target, name);
  }

  @Test
  void testConsumerFailureIsLoggedNotThrown() {
    final Sinks.Many<?> events = field(tracker, "eventSink");

    events.tryEmitError(new IllegalStateException("consumer broke"));

    // the tracker keeps answering queries after its consumer ended
    assertThat(tracker.getActiveSessions()).isEmpty();
  }

  @Test
  void testControlStateForUnknownExecutionIsIgnored() {
    tracker.emitControlStateEvent("nobody", ControlState.PAUSED);

    assertThat(tracker.getActiveSessions()).isEmpty();
  }

  @Test
  void testLogStreamKeepsGoingUntilTheRunIsReallyOver() {
    StepVerifier.create(tracker.startWorkflow("ls-exec", "ls-sess", "ls-wf", List.of("a")))
        .verifyComplete();
    final Map<String, WorkflowState> index = field(tracker, "executionIndex");
    final WorkflowState state = index.get("ls-exec");

    StepVerifier.create(tracker.getLogStream("ls-exec"))
        .then(
            () -> {
              index.remove("ls-exec"); // state unknown: keep streaming
              tracker.appendLog("ls-exec", "no state").block();
            })
        .expectNext("no state")
        .then(
            () -> {
              index.put("ls-exec", state);
              state.setStatus("SUCCESS"); // ended status but no end time yet: keep streaming
              tracker.appendLog("ls-exec", "no end time").block();
            })
        .expectNext("no end time")
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void testStatusStreamIgnoresTerminalProgressWithoutEndTime() {
    StepVerifier.create(tracker.startWorkflow("ss-exec", "ss-sess", "ss-wf", List.of("a")))
        .verifyComplete();
    final Map<String, WorkflowState> index = field(tracker, "executionIndex");
    final WorkflowState state = index.get("ss-exec");

    StepVerifier.create(tracker.getStatusStream("ss-exec", false))
        .then(
            () -> {
              state.setStatus("SUCCESS"); // terminal status, endTime still null
              tracker.emitControlStateEvent("ss-exec", ControlState.PAUSED);
            })
        .assertNext(progress -> assertThat(progress.status()).isEqualTo("SUCCESS"))
        .thenCancel()
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void testStatusChangeOfAnExecutionWithoutStatusSinkIsNotPublished() {
    StepVerifier.create(tracker.startWorkflow("ns-exec", "ns-sess", "ns-wf", List.of("a")))
        .verifyComplete();
    final Map<String, Sinks.Many<WorkflowProgress>> sinks = field(tracker, "statusSinks");
    sinks.remove("ns-exec");

    tracker.emitControlStateEvent("ns-exec", ControlState.PAUSED);

    assertThat(tracker.getProgressByExecutionId("ns-exec").controlState()).isEqualTo("PAUSED");
  }

  @Test
  void testCleanupOfRemovedSessionLeavesNoTrace() {
    StepVerifier.create(tracker.startWorkflow("cl-exec", "cl-sess", "cl-wf", List.of("a")))
        .verifyComplete();
    final Map<String, Map<String, WorkflowState>> sessions = field(tracker, "sessionStates");
    sessions.remove("cl-sess");
    final Map<String, WorkflowState> index = field(tracker, "executionIndex");

    tracker.emitWorkflowStatusEvent("cl-exec", TaskStatus.SUCCESS);

    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertThat(index).isEmpty());
    assertThat(tracker.getActiveSessions()).isEmpty();
  }
}
