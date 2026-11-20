// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.NoArgsConstructor;
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
  void theWaitForASlotIsNotPartOfTheStart() {
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStatus("e1", TaskStatus.QUEUED, false, null, null);
    await().pollDelay(java.time.Duration.ofMillis(80)).until(() -> true);
    telemetry.runStatus("e1", TaskStatus.RUNNING, false, null, null);

    assertThat(telemetry.pluginStarted("e1")).isLessThan(java.time.Duration.ofMillis(40));
    assertThat(meters.get("yukta.execution.queue.wait").timer().totalTime(TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(80);
  }

  @Test
  void aRunThatIsStillQueuedWhenItsFirstPluginIsCalledDoesNotCountTheWait() {
    // the slot is given before the RUNNING event is applied
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStatus("e1", TaskStatus.QUEUED, false, null, null);
    await().pollDelay(java.time.Duration.ofMillis(80)).until(() -> true);

    assertThat(telemetry.pluginStarted("e1")).isLessThan(java.time.Duration.ofMillis(40));
  }

  @Test
  void aRunThatEndsBeforeAnyPluginRanLeavesNothingBehind() {
    final Instant now = Instant.now();
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStatus("e1", TaskStatus.SUCCESS, true, now.minusSeconds(1), now);

    assertThat(telemetry.pluginStarted("e1")).isNull();
  }

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
