// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.model.execution.TaskProgress;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RunTelemetryTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final RunTelemetry telemetry = new RunTelemetry(meters, ObservationRegistry.create());

  private static TaskStatusEvent event(final String exec, final String node, final String status) {
    return TaskStatusEvent.create(exec, node, "MOD", status, Map.of());
  }

  @Test
  void finishingOneRunLeavesTheNodeSpansOfOtherRunsAlone() {
    telemetry.runStarted("e1", "s", "w");
    telemetry.runStarted("e2", "s", "w");
    telemetry.traceNode(event("e1", "n", "RUNNING"), false);
    telemetry.traceNode(event("e2", "n", "RUNNING"), false);

    final LocalDateTime now = LocalDateTime.now();
    telemetry.runStatus("e1", "FAILURE", true, now.minusSeconds(1), now);

    // e2's node can still be ended normally, and e1's run is timed once
    telemetry.traceNode(event("e2", "n", "SUCCESS"), true);
    telemetry.runStatus("e2", "SUCCESS", true, now.minusSeconds(1), now);
    assertThat(meters.get("yukta.execution.duration").tag("status", "FAILURE").timer().count())
        .isEqualTo(1);
  }

  @Test
  void queueWaitIsTimedOnlyForRunsThatWaited() {
    telemetry.runStatus("e1", "RUNNING", false, null, null);
    assertThat(meters.find("yukta.execution.queue.wait").timer()).isNull();

    telemetry.runStatus("e1", "QUEUED", false, null, null);
    telemetry.runStatus("e1", "RUNNING", false, null, null);
    assertThat(meters.get("yukta.execution.queue.wait").timer().count()).isEqualTo(1);
  }

  @Test
  void nodeDurationIsOnlyRecordedWhenTheNodeActuallyRan() {
    telemetry.nodeEnded(null);
    final LocalDateTime now = LocalDateTime.now();
    telemetry.nodeEnded(new TaskProgress("a", "MOD", "SUCCESS", null, now, Map.of()));
    telemetry.nodeEnded(new TaskProgress("b", "MOD", "SUCCESS", now, null, Map.of()));
    assertThat(meters.find("yukta.node.duration").timer()).isNull();

    telemetry.nodeEnded(
        new TaskProgress("c", "MOD", "SUCCESS", now.minusSeconds(1), now, Map.of()));
    telemetry.retried("MOD");
    assertThat(meters.get("yukta.node.duration").timer().count()).isEqualTo(1);
    assertThat(meters.get("yukta.node.executions").counter().count()).isEqualTo(3);
    assertThat(meters.get("yukta.node.retries").counter().count()).isEqualTo(1);
  }

  @Test
  void terminalEventForUnknownNodeIsIgnored() {
    telemetry.traceNode(event("e1", "ghost", "SUCCESS"), true);
    telemetry.traceNode(event("e1", "ghost", "PENDING"), false);

    assertThat(meters.getMeters()).isEmpty();
  }

  @Test
  void runWithoutSpan_isStillTimedWhenItEnds() {
    final LocalDateTime now = LocalDateTime.now();

    telemetry.runStatus("ghost", "SUCCESS", true, now.minusSeconds(1), now);

    assertThat(meters.get("yukta.execution.duration").tag("status", "SUCCESS").timer().count())
        .isEqualTo(1);
  }
}
