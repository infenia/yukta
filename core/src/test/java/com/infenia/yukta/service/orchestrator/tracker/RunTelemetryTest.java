// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RunTelemetryTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final RunTelemetry telemetry = new RunTelemetry(meters, ObservationRegistry.create());

  private static TaskStatusEvent event(
      final String exec, final String node, final TaskStatus status) {
    return TaskStatusEvent.create(exec, node, "MOD", status, Map.of());
  }

  @Test
  void finishingOneRunLeavesTheNodeSpansOfOtherRunsAlone() {
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStarted("e2", "s", "w");
    telemetry.traceNode(event("e1", "n", TaskStatus.RUNNING), false);
    telemetry.traceNode(event("e2", "n", TaskStatus.RUNNING), false);

    final Instant now = Instant.now();
    telemetry.runStatus("e1", TaskStatus.FAILURE, true, now.minusSeconds(1), now);

    // e2's node can still be ended normally, and e1's run is timed once
    telemetry.traceNode(event("e2", "n", TaskStatus.SUCCESS), true);
    telemetry.runStatus("e2", TaskStatus.SUCCESS, true, now.minusSeconds(1), now);
    assertThat(meters.get("yukta.execution.duration").tag("status", "FAILURE").timer().count())
        .isEqualTo(1);
  }

  @Test
  void queueWaitIsTimedOnlyForRunsThatWaited() {
    telemetry.runStatus("e1", TaskStatus.RUNNING, false, null, null);
    assertThat(meters.find("yukta.execution.queue.wait").timer()).isNull();

    telemetry.runStatus("e1", TaskStatus.QUEUED, false, null, null);
    telemetry.runStatus("e1", TaskStatus.RUNNING, false, null, null);
    assertThat(meters.get("yukta.execution.queue.wait").timer().count()).isEqualTo(1);
  }

  @Test
  void theStartOfTheRunIsTimedOnceFromItsRegistration() {
    assertThat(telemetry.pluginStarted("never-registered")).isNull();

    telemetry.runStarted("e1", "s", "w");

    assertThat(telemetry.pluginStarted("e1")).isGreaterThanOrEqualTo(java.time.Duration.ZERO);
    assertThat(telemetry.pluginStarted("e1")).isNull();
    assertThat(meters.get("yukta.engine.start").timer().count()).isEqualTo(1);
  }

  @Test
  void waitForSlotIsNotPartOfTheStart() {
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStatus("e1", TaskStatus.QUEUED, false, null, null);
    await().pollDelay(java.time.Duration.ofMillis(80)).until(() -> true);
    telemetry.runStatus("e1", TaskStatus.RUNNING, false, null, null);

    assertThat(telemetry.pluginStarted("e1")).isLessThan(java.time.Duration.ofMillis(40));
    assertThat(meters.get("yukta.execution.queue.wait").timer().totalTime(TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(80);
  }

  @Test
  void runStillQueuedWhenFirstPluginIsCalledDoesNotCountTheWait() {
    // the slot is given before the RUNNING event is applied
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStatus("e1", TaskStatus.QUEUED, false, null, null);
    await().pollDelay(java.time.Duration.ofMillis(80)).until(() -> true);

    assertThat(telemetry.pluginStarted("e1")).isLessThan(java.time.Duration.ofMillis(40));
  }

  @Test
  void runThatEndsBeforeAnyPluginRanLeavesNothingBehind() {
    final Instant now = Instant.now();
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStatus("e1", TaskStatus.SUCCESS, true, now.minusSeconds(1), now);

    assertThat(telemetry.pluginStarted("e1")).isNull();
  }

  @Test
  void runStartedReturnsTheTraceIdOfItsSpanWhenTheTracerIsConfigured() {
    assertThat(telemetry.runStarted("e1", "s", "w")).isNull();

    assertThat(tracedRun("trace-1").runStarted("e2", "s", "w")).isEqualTo("trace-1");
    assertThat(tracedRun("").runStarted("e3", "s", "w")).isNull();
  }

  /** A registry whose handler gives every observation a tracing context with this trace id. */
  private RunTelemetry tracedRun(final String traceId) {
    final ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public boolean supportsContext(final Observation.Context context) {
                return true;
              }

              @Override
              public void onStart(final Observation.Context context) {
                final TraceContext trace = mock(TraceContext.class);
                when(trace.traceId()).thenReturn(traceId);
                final Span span = mock(Span.class);
                when(span.context()).thenReturn(trace);
                context.put(TracingContext.class, tracingWith(span));
              }

              private TracingContext tracingWith(final Span span) {
                final TracingContext tracing = new TracingContext();
                tracing.setSpan(span);
                return tracing;
              }
            });
    return new RunTelemetry(meters, registry);
  }

  @Test
  void droppedEventsAreCountedByTheirType() {
    telemetry.dropped("yukta.tracker.dropped", "feed");
    telemetry.edgeDropped("CONDITION");
    telemetry.edgeDropped("CONDITION");

    assertThat(meters.get("yukta.tracker.dropped").tag("type", "feed").counter().count())
        .isEqualTo(1);
    assertThat(meters.get("yukta.edge.dropped").tag("type", "CONDITION").counter().count())
        .isEqualTo(2);
  }

  @Nested
  /* default */ final class SpanTimings {
    @Test
    void handOffIsTimed() {
      telemetry.handedOff(java.time.Duration.ofMillis(4));

      assertThat(meters.get("yukta.engine.handoff").timer().count()).isEqualTo(1);
    }

    @Test
    void nodeDurationIsOnlyRecordedWhenTheNodeActuallyRan() {
      telemetry.nodeEnded(null);
      final Instant now = Instant.now();
      telemetry.nodeEnded(new TaskProgress("a", "MOD", TaskStatus.SUCCESS, null, now, Map.of()));
      telemetry.nodeEnded(new TaskProgress("b", "MOD", TaskStatus.SUCCESS, now, null, Map.of()));
      assertThat(meters.find("yukta.node.duration").timer()).isNull();

      telemetry.nodeEnded(
          new TaskProgress("c", "MOD", TaskStatus.SUCCESS, now.minusSeconds(1), now, Map.of()));
      telemetry.retried("MOD");
      assertThat(meters.get("yukta.node.duration").timer().count()).isEqualTo(1);
      assertThat(meters.get("yukta.node.executions").counter().count()).isEqualTo(3);
      assertThat(meters.get("yukta.node.retries").counter().count()).isEqualTo(1);
    }

    @Test
    void terminalEventForUnknownNodeIsIgnored() {
      telemetry.traceNode(event("e1", "ghost", TaskStatus.SUCCESS), true);
      telemetry.traceNode(event("e1", "ghost", TaskStatus.PENDING), false);

      assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void runWithoutSpan_isStillTimedWhenItEnds() {
      final Instant now = Instant.now();

      telemetry.runStatus("ghost", TaskStatus.SUCCESS, true, now.minusSeconds(1), now);

      assertThat(meters.get("yukta.execution.duration").tag("status", "SUCCESS").timer().count())
          .isEqualTo(1);
    }
  }
}
