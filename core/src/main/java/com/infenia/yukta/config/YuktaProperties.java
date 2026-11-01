// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.service.control.ExecutionScheduler.OnLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of the core module under {@code yukta.*}. A value out of range stops start-up with
 * the name of the setting, and the property names appear in the IDE through the generated
 * configuration metadata. The old {@code control.bus.*} and {@code workflow.cache.*} names are
 * mapped to {@code yukta.control-bus.*} and {@code yukta.cache.*} by {@link LegacyPropertyAliases},
 * for one release.
 *
 * @param execution run limits and queueing
 * @param executionLog the in-memory execution log
 * @param restart checkpoints kept for restarts
 * @param streaming the status history of live streams
 * @param supervisor supervisor hosting
 * @param stop draining stop
 * @param tracker the task tracker
 * @param start start idempotency
 * @param results results kept for retrieval
 * @param workers remote worker registry
 * @param controlBus the plugin-level control bus
 * @param cache the prepared workflow cache
 */
@Validated
@ConfigurationProperties(prefix = "yukta")
public record YuktaProperties(
    @Valid @DefaultValue Execution execution,
    @Valid @DefaultValue ExecutionLog executionLog,
    @Valid @DefaultValue Restart restart,
    @Valid @DefaultValue Streaming streaming,
    @Valid @DefaultValue Supervisor supervisor,
    @Valid @DefaultValue Stop stop,
    @Valid @DefaultValue Tracker tracker,
    @Valid @DefaultValue Start start,
    @Valid @DefaultValue Results results,
    @Valid @DefaultValue Workers workers,
    @Valid @DefaultValue ControlBus controlBus,
    @Valid @DefaultValue Cache cache) {

  /**
   * Run limits; 0 means no limit.
   *
   * @param maxConcurrent global limit
   * @param maxConcurrentChildren child runs (sub-workflows) alive at once, apart from the slots
   * @param maxConcurrentPerWorkflow per-workflow limit
   * @param maxConcurrentPerSession per-session limit
   * @param queueCapacity how many starts may wait
   * @param queueCapacityPerWorkflow how many starts of one workflow may wait
   * @param onLimit what to do with a start that does not fit
   * @param shutdownTimeout how long running executions are given on shutdown
   * @param queueTimeout longest a start may wait for a slot, zero for no limit
   * @param maxStartsPerMinute starts per workflow and minute
   */
  public record Execution(
      @Min(0) @DefaultValue("0") int maxConcurrent,
      @Min(0) @DefaultValue("0") int maxConcurrentChildren,
      @Min(0) @DefaultValue("0") int maxConcurrentPerWorkflow,
      @Min(0) @DefaultValue("0") int maxConcurrentPerSession,
      @Min(0) @DefaultValue("100") int queueCapacity,
      @Min(0) @DefaultValue("0") int queueCapacityPerWorkflow,
      @NotNull @DefaultValue("QUEUE") OnLimit onLimit,
      @NotNull @DefaultValue("30s") Duration shutdownTimeout,
      @NotNull @DefaultValue("0") Duration queueTimeout,
      @Min(0) @DefaultValue("0") int maxStartsPerMinute) {}

  /**
   * The in-memory execution log.
   *
   * @param retainedRuns runs kept when the log is trimmed
   * @param compactAfter appended events after which the log is trimmed
   */
  public record ExecutionLog(
      @Min(1) @DefaultValue("500") int retainedRuns,
      @Min(1) @DefaultValue("10000") int compactAfter) {}

  /**
   * Checkpoints kept for restarts.
   *
   * @param messagesPerNode messages kept per node and execution
   * @param bytesPerNode payload bytes kept per node and execution
   * @param spillDir directory for output beyond the caps; empty to refuse such restarts
   * @param autoResume whether interrupted runs are resumed on their own
   * @param retainedExecutions executions whose outputs are kept
   */
  public record Restart(
      @Min(1) @DefaultValue("200") int messagesPerNode,
      @Min(1) @DefaultValue("4194304") long bytesPerNode,
      @DefaultValue("") String spillDir,
      @DefaultValue("false") boolean autoResume,
      @Min(1) @DefaultValue("10") int retainedExecutions) {}

  /**
   * The status history of live streams.
   *
   * @param statusHistoryTtlMinutes minutes a snapshot is kept (1 to 30)
   * @param statusHistoryMaxPerRun snapshots kept per execution
   * @param statusHistoryMaxExecutions executions remembered
   */
  public record Streaming(
      @Min(1) @Max(30) @DefaultValue("5") int statusHistoryTtlMinutes,
      @Min(1) @DefaultValue("200") int statusHistoryMaxPerRun,
      @Min(1) @DefaultValue("1000") int statusHistoryMaxExecutions) {}

  /**
   * Supervisor hosting.
   *
   * @param dryRun only record commands instead of applying them
   * @param maxCommandsPerSecond most commands one supervisor may issue per second
   * @param bufferSize events a supervisor may have waiting before events are dropped for it
   * @param reviewTimeout longest a supervisor may take to answer a review
   */
  public record Supervisor(
      @DefaultValue("false") boolean dryRun,
      @Min(1) @DefaultValue("20") int maxCommandsPerSecond,
      @Min(1) @DefaultValue("10000") int bufferSize,
      @NotNull @DefaultValue("5s") Duration reviewTimeout) {}

  /**
   * Draining stop.
   *
   * @param drainTimeout how long a drain may take before falling back to a cancel
   */
  public record Stop(@NotNull @DefaultValue("30s") Duration drainTimeout) {}

  /**
   * The task tracker.
   *
   * @param sessionTtl how long an idle session is kept; unset keeps every session
   * @param maxSessions most sessions kept in memory, 0 for all
   * @param cleanupTtl how long execution data is kept after its terminal status
   */
  public record Tracker(
      @Nullable Duration sessionTtl,
      @Min(0) @DefaultValue("0") int maxSessions,
      @NotNull @DefaultValue("10m") Duration cleanupTtl) {}

  /**
   * Start idempotency.
   *
   * @param idempotencyTtl how long a key remembers its execution
   * @param idempotencyMaxKeys most keys remembered
   */
  public record Start(
      @NotNull @DefaultValue("10m") Duration idempotencyTtl,
      @Min(1) @DefaultValue("100000") long idempotencyMaxKeys) {}

  /**
   * Results kept for retrieval.
   *
   * @param retainedEvents events kept per execution for replay
   * @param retention how long results stay after they were last used
   * @param maxExecutions finished executions kept at most
   */
  public record Results(
      @Min(1) @DefaultValue("1000") int retainedEvents,
      @NotNull @DefaultValue("1h") Duration retention,
      @Min(1) @DefaultValue("200") int maxExecutions) {}

  /**
   * The remote worker registry.
   *
   * @param lease how long a heartbeat keeps a worker
   */
  public record Workers(@NotNull @DefaultValue("15s") Duration lease) {}

  /**
   * The plugin-level control bus.
   *
   * @param batchSize signals handled per batch
   * @param batchTimeout longest wait to fill a batch
   * @param bufferSize buffer for signals
   */
  public record ControlBus(
      @Min(1) @DefaultValue("100") int batchSize,
      @NotNull @DefaultValue("50ms") Duration batchTimeout,
      @Min(1) @DefaultValue("256") int bufferSize) {}

  /**
   * The prepared workflow cache.
   *
   * @param ttl how long an unused prepared workflow stays cached
   * @param maxSize most workflows kept
   */
  public record Cache(
      @NotNull @DefaultValue("10m") Duration ttl, @Min(1) @DefaultValue("1000") long maxSize) {}
}
