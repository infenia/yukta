// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.event.TaskStatusEvent;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The metrics and observations of runs and nodes, kept apart from the tracker's state.
 *
 * <p>Each run is one {@code yukta.run} observation and each node run a child {@code yukta.node}
 * observation; the meters are listed in the module guide.
 */
final class RunTelemetry {

  /** Tag of a run or node outcome. */
  private static final String STATUS = "status";

  /** Tag of the plugin type. */
  private static final String MODULE = "module";

  /** Where meters are registered. */
  private final MeterRegistry meters;

  /** Turns observations into spans when a tracer is configured. */
  private final ObservationRegistry observations;

  /** Observations of runs that have not ended, by execution; parents of the node observations. */
  private final Map<String, Observation> runSpans = new ConcurrentHashMap<>();

  /** Observations of nodes that are running, by execution and node. */
  private final Map<String, Observation> nodeSpans = new ConcurrentHashMap<>();

  /** When a run began waiting for a slot (System.nanoTime), by execution. */
  private final Map<String, Long> queuedAt = new ConcurrentHashMap<>();

  /* default */ RunTelemetry(final MeterRegistry meters, final ObservationRegistry observations) {
    this.meters = meters;
    this.observations = observations;
  }

  /* default */ void runStarted(
      final String executionId, final String sessionId, final String workflowId) {
    runSpans.put(
        executionId,
        Observation.createNotStarted("yukta.run", observations)
            .contextualName("run " + workflowId)
            .highCardinalityKeyValue("executionId", executionId)
            .highCardinalityKeyValue("sessionId", sessionId)
            .highCardinalityKeyValue("workflowId", workflowId)
            .start());
  }

  /** Notes the run status: times the wait in the queue, and the run itself once it ended. */
  /* default */ void runStatus(
      final String executionId,
      final String status,
      final boolean terminal,
      final LocalDateTime started,
      final LocalDateTime ended) {
    if (TaskStatus.QUEUED.name().equals(status)) {
      queuedAt.put(executionId, System.nanoTime());
    } else if (TaskStatus.RUNNING.name().equals(status)) {
      final Long since = queuedAt.remove(executionId);
      if (since != null) {
        meters
            .timer("yukta.execution.queue.wait")
            .record(Duration.ofNanos(System.nanoTime() - since));
      }
    }
    if (terminal) {
      queuedAt.remove(executionId);
      closeNodeSpans(executionId);
      meters
          .timer("yukta.execution.duration", STATUS, status)
          .record(Duration.between(started, ended));
      final Observation run = runSpans.remove(executionId);
      if (run != null) {
        run.lowCardinalityKeyValue(STATUS, status);
        run.stop();
      }
    }
  }

  /** Opens an observation when a node starts running and closes it when the node ends. */
  /* default */ void traceNode(final TaskStatusEvent event, final boolean terminal) {
    final String key = event.executionId() + "\0" + event.nodeId();
    if (TaskStatus.RUNNING.name().equals(event.status())) {
      nodeSpans.computeIfAbsent(
          key,
          _ ->
              Observation.createNotStarted("yukta.node", observations)
                  .parentObservation(runSpans.get(event.executionId()))
                  .contextualName("node " + event.nodeId())
                  .lowCardinalityKeyValue(MODULE, String.valueOf(event.module()))
                  .highCardinalityKeyValue("executionId", event.executionId())
                  .highCardinalityKeyValue("nodeId", event.nodeId())
                  .start());
    } else if (terminal) {
      final Observation span = nodeSpans.remove(key);
      if (span != null) {
        span.lowCardinalityKeyValue(STATUS, event.status());
        span.stop();
      }
    }
  }

  /** Stops observations of nodes that were finalized with the run instead of ending themselves. */
  private void closeNodeSpans(final String executionId) {
    final String prefix = executionId + "\0";
    nodeSpans
        .keySet()
        .removeIf(
            key -> {
              if (!key.startsWith(prefix)) {
                return false;
              }
              final Observation span = nodeSpans.get(key);
              span.lowCardinalityKeyValue(STATUS, TaskStatus.CANCELLED.name());
              span.stop();
              return true;
            });
  }

  /** Counts the node outcome by plugin and status, and times nodes that actually ran. */
  /* default */ void nodeEnded(final TaskProgress task) {
    if (task == null) {
      return;
    }
    meters
        .counter("yukta.node.executions", MODULE, task.module(), STATUS, task.status())
        .increment();
    if (task.startTime() != null && task.endTime() != null) {
      meters
          .timer("yukta.node.duration", MODULE, task.module())
          .record(Duration.between(task.startTime(), task.endTime()));
    }
  }

  /* default */ void retried(final String module) {
    meters.counter("yukta.node.retries", MODULE, module).increment();
  }

  /* default */ void dropped(final String name, final String type) {
    meters.counter(name, "type", type).increment();
  }

  /* default */ void edgeDropped(final String type, final String nodeId) {
    meters.counter("yukta.edge.dropped", "type", type, "node", nodeId).increment();
  }

  /* default */ void logAppendFailed(final String name, final String type) {
    meters.counter(name, "type", type).increment();
  }
}
