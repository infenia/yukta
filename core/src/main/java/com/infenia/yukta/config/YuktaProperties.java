// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.model.control.OnLimit;
import com.infenia.yukta.model.input.RouteSettings;
import com.infenia.yukta.model.notify.NotificationRuleSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * The settings of the core module under {@code yukta.*}. A value out of range stops start-up with
 * the name of the setting, and the property names appear in the IDE through the generated
 * configuration metadata.
 *
 * @param execution run limits and queueing
 * @param executionLog the in-memory execution log
 * @param restart checkpoints kept for restarts
 * @param streaming the status history of live streams
 * @param supervisor supervisor hosting
 * @param input input requests: limits, deadlines and the in-memory store
 * @param notifications the inbox: retention, rules and outbound channels
 * @param stop draining stop
 * @param start start idempotency
 * @param results results kept for retrieval
 * @param workers remote worker registry
 * @param controlBus the plugin-level control bus
 * @param cache the prepared workflow cache
 * @param control control commands that wait for approval
 * @param agents conversations with terminal AI agents: limits, retention and the terminal tier
 */
@Validated
@ConfigurationProperties(prefix = "yukta")
public record YuktaProperties(
    @Valid @DefaultValue Execution execution,
    @Valid @DefaultValue ExecutionLog executionLog,
    @Valid @DefaultValue Restart restart,
    @Valid @DefaultValue Streaming streaming,
    @Valid @DefaultValue Supervisor supervisor,
    @Valid @DefaultValue Input input,
    @Valid @DefaultValue Notifications notifications,
    @Valid @DefaultValue Stop stop,
    @Valid @DefaultValue Start start,
    @Valid @DefaultValue Results results,
    @Valid @DefaultValue Workers workers,
    @Valid @DefaultValue Cache cache,
    @Valid @DefaultValue Control control,
    @Valid @DefaultValue Agents agents) {

  /** The default cap of the bounded counts: messages, attachments and executions kept. */
  private static final String DEFAULT_CAP = "200";

  /**
   * Control commands.
   *
   * @param approval commands that a supervisor's review parks until someone approves them
   */
  public record Control(@Valid @DefaultValue Approval approval) {}

  /**
   * Parked commands.
   *
   * @param ttl how long an approval request waits when the review does not say; never longer than
   *     {@code yukta.input.max-ttl}
   */
  public record Approval(@NotNull @DefaultValue("10m") Duration ttl) {

    /** Checks that the time to live is positive. */
    public Approval {
      if (ttl == null || ttl.isZero() || ttl.isNegative()) {
        throw new IllegalArgumentException("yukta.control.approval.ttl must be positive");
      }
    }
  }

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
   * @param timeout longest a run may take, whatever its activity; a paused run does not count
   */
  public record Execution(
      @Min(0) @DefaultValue("0") int maxConcurrent,
      @Min(0) @DefaultValue("0") int maxConcurrentChildren,
      @Min(0) @DefaultValue("0") int maxConcurrentPerWorkflow,
      @Min(0) @DefaultValue("0") int maxConcurrentPerSession,
      @Min(0) @DefaultValue("100") int queueCapacity,
      @Min(0) @DefaultValue("0") int queueCapacityPerWorkflow,
      @NotNull @DefaultValue("QUEUE") OnLimit onLimit,
      @NotNull @DurationMin(nanos = 0) @DefaultValue("30s") Duration shutdownTimeout,
      @NotNull @DurationMin(nanos = 0) @DefaultValue("0") Duration queueTimeout,
      @Min(0) @DefaultValue("0") int maxStartsPerMinute,
      @NotNull @DefaultValue("1h") Duration timeout) {}

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
   * @param payloadPackages extra class name prefixes whose payloads a resume restores with their
   *     type (a third-party plugin's own classes); others come back as JSON
   */
  public record Restart(
      @Min(1) @DefaultValue(DEFAULT_CAP) int messagesPerNode,
      @Min(1) @DefaultValue("4194304") long bytesPerNode,
      @DefaultValue("") String spillDir,
      @DefaultValue("false") boolean autoResume,
      @Min(1) @DefaultValue("10") int retainedExecutions,
      @DefaultValue List<String> payloadPackages) {}

  /**
   * The status history of live streams.
   *
   * @param statusHistoryTtlMinutes minutes a snapshot is kept (1 to 30)
   * @param statusHistoryMaxPerRun snapshots kept per execution
   * @param statusHistoryMaxExecutions executions remembered
   */
  public record Streaming(
      @Min(1) @Max(30) @DefaultValue("5") int statusHistoryTtlMinutes,
      @Min(1) @DefaultValue(DEFAULT_CAP) int statusHistoryMaxPerRun,
      @Min(1) @DefaultValue("1000") int statusHistoryMaxExecutions) {}

  /**
   * Supervisor hosting.
   *
   * @param dryRun only record commands instead of applying them
   * @param maxCommandsPerSecond most commands one supervisor may issue per second
   * @param bufferSize events a supervisor may have waiting before events are dropped for it
   * @param reviewTimeout longest a supervisor may take to answer a review
   * @param maxAttachments most attachments at once, manual and from definitions together
   * @param turnAround the guard against a supervisor flapping against itself
   * @param defaultSupervisor the lenient default supervisor
   * @param lockHolders names of the API keys that may change or remove a locked assignment; empty
   *     lets any administrator do it, but only by asking to unlock
   */
  public record Supervisor(
      @DefaultValue("false") boolean dryRun,
      @Min(1) @DefaultValue("20") int maxCommandsPerSecond,
      @Min(1) @DefaultValue("10000") int bufferSize,
      @NotNull @DurationMin(nanos = 0) @DefaultValue("5s") Duration reviewTimeout,
      @Min(1) @DefaultValue(DEFAULT_CAP) int maxAttachments,
      @Valid @NotNull @DefaultValue TurnAround turnAround,
      @Name("default") @Valid @NotNull @DefaultValue DefaultSupervisor defaultSupervisor,
      @NotNull @DefaultValue List<String> lockHolders) {

    /** Keeps an unmodifiable copy of the lock holders. */
    public Supervisor {
      lockHolders = List.copyOf(lockHolders);
    }
  }

  /**
   * The lenient default supervisor, which lets people control what no supervisor owns. Turn it off
   * in production so that every workflow must name its supervisor; an unowned workflow then runs
   * without one and people cannot control it.
   *
   * @param enabled whether people may control units that nobody owns
   */
  public record DefaultSupervisor(@DefaultValue("true") boolean enabled) {}

  /**
   * The guard against a supervisor flapping against itself.
   *
   * @param period the period in which turn-arounds of a target are counted
   * @param limit most turn-arounds of one target by one supervisor in the period
   * @param cooldown how long a supervisor over the limit is blocked on that target
   */
  public record TurnAround(
      @NotNull @DefaultValue("60s") Duration period,
      @Min(1) @DefaultValue("4") int limit,
      @NotNull @DefaultValue("5m") Duration cooldown) {

    /** Checks that the durations are positive. */
    public TurnAround {
      if (period.isZero() || period.isNegative()) {
        throw new IllegalArgumentException("yukta.supervisor.turn-around.period must be positive");
      }
      if (cooldown.isZero() || cooldown.isNegative()) {
        throw new IllegalArgumentException(
            "yukta.supervisor.turn-around.cooldown must be positive");
      }
    }
  }

  /**
   * Input requests: what a plugin may ask, how long a request waits and how many are kept.
   *
   * @param defaultTtl how long a request lives when its spec does not say; at most {@code maxTtl}
   * @param maxTtl the longest a request may live; a longer ttl is refused
   * @param maxOpenPerExecution most open requests one run may have
   * @param maxOpenPerSession most open requests one session may have
   * @param memoryMax most finished requests the in-memory store keeps, oldest evicted first
   * @param sweepInterval how often the sweeper looks for requests past their deadline
   * @param retention how long a finished request and its answer are kept before the hourly purge
   *     removes them
   * @param redactKeys context keys whose value is replaced, besides the built-in secret names
   * @param routes the named routes (tiers of responders); the one called {@code default} applies
   *     when a request and its session name none
   */
  public record Input(
      @NotNull @DefaultValue("1h") Duration defaultTtl,
      @NotNull @DefaultValue("24h") Duration maxTtl,
      @Min(1) @DefaultValue("5") int maxOpenPerExecution,
      @Min(1) @DefaultValue("50") int maxOpenPerSession,
      @Min(1) @DefaultValue("10000") int memoryMax,
      @NotNull @DefaultValue("1s") Duration sweepInterval,
      @NotNull @DefaultValue("30d") Duration retention,
      @NotNull @DefaultValue List<String> redactKeys,
      @NotNull @DefaultValue Map<String, RouteSettings> routes) {

    /** Checks that the durations are positive and that the default ttl fits the maximum. */
    public Input {
      requirePositive("yukta.input.default-ttl", defaultTtl);
      requirePositive("yukta.input.max-ttl", maxTtl);
      requirePositive("yukta.input.sweep-interval", sweepInterval);
      requirePositive("yukta.input.retention", retention);
      if (maxTtl.compareTo(defaultTtl) < 0) {
        throw new IllegalArgumentException(
            "yukta.input.max-ttl must not be shorter than yukta.input.default-ttl");
      }
      redactKeys = List.copyOf(redactKeys);
      routes = Map.copyOf(routes);
      // checks every route at start-up: a bad tier or responder stops the application
      routes.forEach((name, settings) -> settings.named(name));
    }

    private static void requirePositive(final String name, final Duration value) {
      if (value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
    }
  }

  /**
   * Notifications: the inbox, what raises one and where else it goes.
   *
   * @param enabled whether notifications are raised at all
   * @param retention how long a resolved action and an info notification are kept
   * @param maxItems most notifications the in-memory store keeps, resolved and info ones evicted
   *     first
   * @param actionGrace how long a question that a supervisor may also answer waits before a person
   *     is told
   * @param rules the named rules; each may override audience and severity and add channels
   * @param channels the outbound channels: type, then name, then that channel's settings
   */
  public record Notifications(
      @DefaultValue("true") boolean enabled,
      @NotNull @DefaultValue("7d") Duration retention,
      @Min(1) @DefaultValue("5000") int maxItems,
      @NotNull @DefaultValue("2s") Duration actionGrace,
      @NotNull @DefaultValue Map<String, NotificationRuleSettings> rules,
      @NotNull @DefaultValue Map<String, Map<String, Map<String, Object>>> channels) {

    /** Checks the durations and every rule: a bad rule stops the application, naming the rule. */
    public Notifications {
      if (retention.isZero() || retention.isNegative()) {
        throw new IllegalArgumentException("yukta.notifications.retention must be positive");
      }
      if (actionGrace.isNegative()) {
        throw new IllegalArgumentException("yukta.notifications.action-grace must not be negative");
      }
      final Map<String, Map<String, Map<String, Object>>> configured = Map.copyOf(channels);
      rules = Map.copyOf(rules);
      channels = configured;
      rules.forEach((name, rule) -> rule.check(name, configured));
    }
  }

  /**
   * Conversations with terminal AI agents (the agent console).
   *
   * @param enabled false: no conversation is recorded and the agent plugin runs as it did before
   *     conversations existed
   * @param retention how long an ended conversation is kept
   * @param maxConversations most conversations the in-memory store keeps; ended ones go first
   * @param maxEventsPerConversation most events kept per conversation; the oldest go first and a
   *     notice says so once
   * @param maxMessageBytes longest text of one event, and of one message sent to an agent
   * @param maxToolBytes longest tool input and tool output kept per event
   * @param terminalRingBytes terminal output kept per conversation
   * @param messagesPerMinute most messages one caller may send to one conversation in a minute
   * @param pty the terminal tier
   */
  public record Agents(
      @DefaultValue("true") boolean enabled,
      @NotNull @DefaultValue("7d") Duration retention,
      @Min(1) @DefaultValue("500") int maxConversations,
      @Min(1) @DefaultValue("5000") int maxEventsPerConversation,
      @Min(1) @DefaultValue("65536") int maxMessageBytes,
      @Min(1) @DefaultValue("4096") int maxToolBytes,
      @Min(1) @DefaultValue("262144") int terminalRingBytes,
      @Min(1) @DefaultValue("30") int messagesPerMinute,
      @Valid @DefaultValue Pty pty) {

    /** Checks that the retention is positive. */
    public Agents {
      if (retention.isZero() || retention.isNegative()) {
        throw new IllegalArgumentException("yukta.agents.retention must be positive");
      }
    }

    /**
     * The terminal tier: a pseudo-terminal for agents with no structured mode.
     *
     * @param enabled whether a terminal agent may run; off by default because a terminal accepts
     *     any keystroke and nothing can review what is typed
     * @param host the executable that provides the {@code pty-host} command
     * @param cols the width of a new terminal, until a client resizes it
     * @param rows the height of a new terminal, until a client resizes it
     */
    public record Pty(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("yukta-cli") String host,
        @Min(1) @Max(1000) @DefaultValue("120") int cols,
        @Min(1) @Max(1000) @DefaultValue("40") int rows) {}
  }

  /**
   * Draining stop.
   *
   * @param drainTimeout how long a drain may take before falling back to a cancel
   */
  public record Stop(@NotNull @DurationMin(nanos = 0) @DefaultValue("30s") Duration drainTimeout) {}

  /**
   * Start idempotency.
   *
   * @param idempotencyTtl how long a key remembers its execution
   * @param idempotencyMaxKeys most keys remembered
   */
  public record Start(
      @NotNull @DurationMin(nanos = 0) @DefaultValue("10m") Duration idempotencyTtl,
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
      @NotNull @DurationMin(nanos = 0) @DefaultValue("1h") Duration retention,
      @Min(1) @DefaultValue(DEFAULT_CAP) int maxExecutions) {}

  /**
   * The remote worker registry.
   *
   * @param lease how long a heartbeat keeps a worker
   */
  public record Workers(@NotNull @DurationMin(nanos = 0) @DefaultValue("15s") Duration lease) {}

  /**
   * The prepared workflow cache.
   *
   * @param ttl how long an unused prepared workflow stays cached
   * @param maxSize most workflows kept
   */
  public record Cache(
      @NotNull @DurationMin(nanos = 0) @DefaultValue("10m") Duration ttl,
      @Min(1) @DefaultValue("1000") long maxSize) {}
}
