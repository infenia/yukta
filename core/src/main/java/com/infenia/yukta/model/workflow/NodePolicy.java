// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Engine-level failure handling of a node, read from the reserved {@code engine} key of the node
 * config. Plugins never see these options; they only describe what the engine does around the
 * plugin.
 *
 * <pre>{@code
 * "engine": {
 *   "retry":   { "maxAttempts": 3, "backoffMs": 200, "maxBackoffMs": 5000,
 *                "retryOn": ["IOException"], "noRetryOn": ["IllegalStateException"] },
 *   "onError": "ROUTE",
 *   "trigger": "ALL_DONE",
 *   "elementTimeoutMs": 20000,
 *   "deadlineMs": 600000,
 *   "failureBudget": { "maxFailures": 10, "windowMs": 60000 }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code retry} (processors): run the plugin again for the failing message, with exponential
 *       backoff and jitter. The plugin is then called once per message, so use it with per-message
 *       processors, not with ones that aggregate over the stream.
 *   <li>{@code onError} (processors): {@code FAIL} (default) fails the node; {@code CONTINUE} drops
 *       the failing message and carries on; {@code ROUTE} sends the failing message, tagged with
 *       its failure, to the node's {@code "error"} output port. The tag holds the node, the reason,
 *       the exception class, the number of attempts and the time of the failure.
 *   <li>{@code trigger} (any node with several parents): {@code ALL_SUCCESS} (default) fails as
 *       soon as one parent fails; {@code ALL_DONE} keeps going on the other parents when one fails;
 *       {@code ONE_SUCCESS} keeps going too, but fails when every parent failed.
 *   <li>{@code elementTimeoutMs} (processors): longest time the plugin may take to answer for one
 *       message; it counts as a failure, so {@code retry} and {@code onError} apply. Off by
 *       default.
 *   <li>{@code deadlineMs} (any node): longest the node may run, counted from its first activity.
 *       Unlike the idle timeout it is not restarted by output or heartbeats; paused time does not
 *       count. Off by default.
 *   <li>{@code failureBudget} (with {@code onError} CONTINUE or ROUTE): when more than {@code
 *       maxFailures} messages fail within {@code windowMs}, the node fails instead of dropping or
 *       routing yet another one, so a poisoned stream does not run on unnoticed.
 *   <li>{@code retry.retryOn} / {@code retry.noRetryOn}: class names (simple or fully qualified,
 *       matched against the exception and its causes' types) that are, or are not, retried. {@code
 *       IllegalArgumentException} is never retried unless it is listed in {@code retryOn}.
 * </ul>
 *
 * @param retry the retry policy, or null for none
 * @param onError what to do with a message that still fails
 * @param trigger how a failing parent affects this node
 * @param elementTimeout longest time for one message, or null for none
 * @param deadline longest time the node may run, or null for none
 * @param failureBudget how many failures the node tolerates, or null for no limit
 */
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidDuplicateLiterals"})
public record NodePolicy(
    @Nullable Retry retry,
    OnError onError,
    JoinRule trigger,
    @Nullable Duration elementTimeout,
    @Nullable Duration deadline,
    @Nullable FailureBudget failureBudget) {

  /** Config key that holds the policy. */
  public static final String CONFIG_KEY = "engine";

  /** Output port that failing messages are routed to by {@link OnError#ROUTE}. */
  public static final String ERROR_PORT = "error";

  /** No retry, fail on error, fail when a parent fails. */
  public static final NodePolicy DEFAULT =
      new NodePolicy(null, OnError.FAIL, JoinRule.ALL_SUCCESS, null, null, null);

  /** Options accepted in the {@code engine} object. */
  private static final Set<String> KEYS =
      Set.of(
          "retry",
          "onError",
          "trigger",
          "elementTimeoutMs",
          "deadlineMs",
          "failureBudget",
          "edgeBuffer",
          "placement");

  /** Options accepted in the {@code retry} object. */
  private static final Set<String> RETRY_KEYS =
      Set.of("maxAttempts", "backoffMs", "maxBackoffMs", "retryOn", "noRetryOn");

  /** Options accepted in the {@code placement} object. */
  private static final Set<String> PLACEMENT_KEYS = Set.of("pool");

  /** Pool a placement without a {@code pool} uses. */
  public static final String DEFAULT_POOL = "default";

  /** Options accepted in the {@code edgeBuffer} object. */
  private static final Set<String> EDGE_BUFFER_KEYS = Set.of("size", "overflow");

  /** Largest accepted edge buffer. */
  private static final int MAX_EDGE_BUFFER = 100_000;

  /** Options accepted in the {@code failureBudget} object. */
  private static final Set<String> BUDGET_KEYS = Set.of("maxFailures", "windowMs");

  /** How many causes of a failure are looked at when matching retry class names. */
  private static final int MAX_CAUSES = 10;

  /** Upper bound for {@code maxAttempts}, so a typo cannot hammer a dependency. */
  private static final int MAX_ATTEMPTS_LIMIT = 10;

  /** Default delay before the first retry. */
  private static final long DEFAULT_BACKOFF_MS = 200;

  /** Default upper bound for the retry delay. */
  private static final long DEFAULT_MAX_BACKOFF_MS = 10_000;

  /**
   * Creates a policy without a deadline.
   *
   * @param retry the retry policy, or null for none
   * @param onError what to do with a message that still fails
   * @param trigger how a failing parent affects this node
   * @param elementTimeout longest time for one message, or null for none
   */
  public NodePolicy(
      @Nullable final Retry retry,
      final OnError onError,
      final JoinRule trigger,
      @Nullable final Duration elementTimeout) {
    this(retry, onError, trigger, elementTimeout, null, null);
  }

  /**
   * Creates a policy without an element timeout.
   *
   * @param retry the retry policy, or null for none
   * @param onError what to do with a message that still fails
   * @param trigger how a failing parent affects this node
   */
  public NodePolicy(@Nullable final Retry retry, final OnError onError, final JoinRule trigger) {
    this(retry, onError, trigger, null);
  }

  /** What to do with a message the plugin could not process. */
  public enum OnError {
    FAIL,
    CONTINUE,
    ROUTE
  }

  /** How a failing parent affects a node that has several parents. */
  public enum JoinRule {
    ALL_SUCCESS,
    ALL_DONE,
    ONE_SUCCESS
  }

  /**
   * Retry settings.
   *
   * @param maxAttempts total attempts including the first one
   * @param backoff delay before the first retry; doubles on each retry
   * @param maxBackoff upper bound for the delay
   */
  public record Retry(
      int maxAttempts,
      Duration backoff,
      Duration maxBackoff,
      Set<String> retryOn,
      Set<String> noRetryOn) {

    /** Keeps immutable copies of the class name sets. */
    public Retry {
      retryOn = Set.copyOf(retryOn);
      noRetryOn = Set.copyOf(noRetryOn);
    }

    /**
     * Retry settings without class filters.
     *
     * @param maxAttempts total attempts including the first one
     * @param backoff delay before the first retry
     * @param maxBackoff upper bound for the delay
     */
    public Retry(final int maxAttempts, final Duration backoff, final Duration maxBackoff) {
      this(maxAttempts, backoff, maxBackoff, Set.of(), Set.of());
    }

    /**
     * Whether a failure is worth another attempt.
     *
     * @param error what the attempt failed with
     * @return true if it should be retried
     */
    public boolean shouldRetry(final Throwable error) {
      if (matches(noRetryOn, error)) {
        return false;
      }
      if (!retryOn.isEmpty()) {
        return matches(retryOn, error);
      }
      return !(error instanceof IllegalArgumentException);
    }

    private static boolean matches(final Set<String> names, final Throwable error) {
      if (names.isEmpty()) {
        return false;
      }
      Throwable cause = error;
      // bounded, so a cause chain that loops back on itself cannot spin forever
      for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++) {
        for (Class<?> type = cause.getClass(); type != null; type = type.getSuperclass()) {
          if (names.contains(type.getName()) || names.contains(type.getSimpleName())) {
            return true;
          }
        }
        cause = cause.getCause();
      }
      return false;
    }
  }

  /**
   * How many failures a node tolerates when it drops or routes failing messages.
   *
   * @param maxFailures failures allowed within the window; one more fails the node
   * @param window the sliding window
   */
  public record FailureBudget(int maxFailures, Duration window) {}

  /**
   * Where a node runs when it is not on the control plane.
   *
   * @param pool the pool of workers that may run it
   */
  public record Placement(String pool) {}

  /** What a full edge buffer does with the message that does not fit. */
  public enum Overflow {
    /** Drop the oldest waiting message. */
    DROP_OLDEST,
    /** Drop the message that just arrived. */
    DROP_NEWEST
  }

  /**
   * A bounded buffer in front of a node: a slow node then drops messages instead of slowing its
   * parents (without it the slowest child paces the fan-out).
   *
   * @param size most messages waiting
   * @param overflow what to drop when it is full
   */
  public record EdgeBuffer(int size, Overflow overflow) {}

  /**
   * Reads the optional {@code engine.placement} of a node config: the node then runs on a worker of
   * the pool instead of on the control plane.
   *
   * @param config the node config, possibly empty
   * @return the placement, or null when the node runs locally
   * @throws IllegalArgumentException with a precise message if it is malformed
   */
  @Nullable
  public static Placement placement(@Nullable final Map<String, Object> config) {
    final Object raw = config == null ? null : config.get(CONFIG_KEY);
    if (raw == null || !asMap(raw, CONFIG_KEY).containsKey("placement")) {
      return null;
    }
    final Map<String, Object> placement =
        asMap(asMap(raw, CONFIG_KEY).get("placement"), "placement");
    rejectUnknown(placement, PLACEMENT_KEYS, "engine.placement");
    final Object pool = placement.getOrDefault("pool", DEFAULT_POOL);
    if (!(pool instanceof final String name) || name.isBlank()) {
      throw new IllegalArgumentException("engine.placement.pool must be a non-empty name");
    }
    return new Placement(name);
  }

  /**
   * Reads the optional {@code engine.edgeBuffer} of a node config.
   *
   * @param config the node config, possibly empty
   * @return the buffer, or null when the node has none
   * @throws IllegalArgumentException with a precise message if it is malformed
   */
  @Nullable
  public static EdgeBuffer edgeBuffer(@Nullable final Map<String, Object> config) {
    final Object raw = config == null ? null : config.get(CONFIG_KEY);
    if (raw == null || !asMap(raw, CONFIG_KEY).containsKey("edgeBuffer")) {
      return null;
    }
    final Map<String, Object> buffer =
        asMap(asMap(raw, CONFIG_KEY).get("edgeBuffer"), "edgeBuffer");
    rejectUnknown(buffer, EDGE_BUFFER_KEYS, "engine.edgeBuffer");
    final long size = number(buffer.get("size"), "engine.edgeBuffer.size", -1);
    if (size < 1 || size > MAX_EDGE_BUFFER) {
      throw new IllegalArgumentException(
          "engine.edgeBuffer.size is required and must be between 1 and " + MAX_EDGE_BUFFER);
    }
    return new EdgeBuffer(
        (int) size,
        parseEnum(buffer.get("overflow"), Overflow.class, "overflow", Overflow.DROP_OLDEST));
  }

  /**
   * Whether the plugin must be called once per message, which retry and onError need.
   *
   * @return true if retry, an element timeout or a non-default onError is set
   */
  public boolean perMessage() {
    return retry != null || elementTimeout != null || onError != OnError.FAIL;
  }

  /**
   * Reads the policy from a node config.
   *
   * @param config the node config, possibly empty
   * @return the policy, {@link #DEFAULT} if the node has none
   * @throws IllegalArgumentException with a precise message if the policy is malformed
   */
  public static NodePolicy from(@Nullable final Map<String, Object> config) {
    final Object raw = config == null ? null : config.get(CONFIG_KEY);
    if (raw == null) {
      return DEFAULT;
    }
    final Map<String, Object> engine = asMap(raw, CONFIG_KEY);
    rejectUnknown(engine, KEYS, CONFIG_KEY);
    final NodePolicy policy =
        new NodePolicy(
            engine.containsKey("retry") ? parseRetry(asMap(engine.get("retry"), "retry")) : null,
            parseEnum(engine.get("onError"), OnError.class, "onError", OnError.FAIL),
            parseEnum(engine.get("trigger"), JoinRule.class, "trigger", JoinRule.ALL_SUCCESS),
            parseMillis(engine.get("elementTimeoutMs"), "engine.elementTimeoutMs"),
            parseMillis(engine.get("deadlineMs"), "engine.deadlineMs"),
            engine.containsKey("failureBudget")
                ? parseBudget(asMap(engine.get("failureBudget"), "failureBudget"))
                : null);
    edgeBuffer(config);
    placement(config);
    if (policy.failureBudget() != null && policy.onError() == OnError.FAIL) {
      throw new IllegalArgumentException(
          "engine.failureBudget needs engine.onError CONTINUE or ROUTE; with FAIL the first"
              + " failure already fails the node");
    }
    return policy;
  }

  private static FailureBudget parseBudget(final Map<String, Object> budget) {
    rejectUnknown(budget, BUDGET_KEYS, "engine.failureBudget");
    final long max = number(budget.get("maxFailures"), "engine.failureBudget.maxFailures", -1);
    final long window = number(budget.get("windowMs"), "engine.failureBudget.windowMs", 0);
    if (max < 0) {
      throw new IllegalArgumentException(
          "engine.failureBudget.maxFailures is required and must be >= 0");
    }
    if (window <= 0) {
      throw new IllegalArgumentException("engine.failureBudget.windowMs must be positive");
    }
    return new FailureBudget((int) Math.min(max, Integer.MAX_VALUE), Duration.ofMillis(window));
  }

  @Nullable
  private static Duration parseMillis(@Nullable final Object value, final String name) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number given && given.doubleValue() != Math.rint(given.doubleValue())) {
      throw new IllegalArgumentException(name + " must be a whole number");
    }
    final long millis = number(value, name, 0);
    if (millis <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return Duration.ofMillis(millis);
  }

  private static Retry parseRetry(final Map<String, Object> retry) {
    rejectUnknown(retry, RETRY_KEYS, "engine.retry");
    final long attempts = number(retry.get("maxAttempts"), "engine.retry.maxAttempts", 3);
    final long backoff =
        number(retry.get("backoffMs"), "engine.retry.backoffMs", DEFAULT_BACKOFF_MS);
    final long max =
        number(retry.get("maxBackoffMs"), "engine.retry.maxBackoffMs", DEFAULT_MAX_BACKOFF_MS);
    if (attempts < 1 || attempts > MAX_ATTEMPTS_LIMIT) {
      throw new IllegalArgumentException(
          "engine.retry.maxAttempts must be between 1 and " + MAX_ATTEMPTS_LIMIT);
    }
    if (backoff < 0 || max < backoff) {
      throw new IllegalArgumentException(
          "engine.retry.backoffMs must be >= 0 and <= engine.retry.maxBackoffMs");
    }
    final Set<String> retryOn = names(retry.get("retryOn"), "engine.retry.retryOn");
    final Set<String> noRetryOn = names(retry.get("noRetryOn"), "engine.retry.noRetryOn");
    if (!java.util.Collections.disjoint(retryOn, noRetryOn)) {
      throw new IllegalArgumentException(
          "engine.retry.retryOn and engine.retry.noRetryOn must not share a class");
    }
    return new Retry(
        (int) attempts, Duration.ofMillis(backoff), Duration.ofMillis(max), retryOn, noRetryOn);
  }

  private static Set<String> names(@Nullable final Object value, final String name) {
    if (value == null) {
      return Set.of();
    }
    if (value instanceof java.util.Collection<?> items
        && items.stream().allMatch(item -> item instanceof String text && !text.isBlank())) {
      return items.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }
    throw new IllegalArgumentException("'" + name + "' must be a list of class names");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(final Object value, final String name) {
    if (value instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new IllegalArgumentException("'" + name + "' must be an object");
  }

  private static void rejectUnknown(
      final Map<String, Object> map, final Set<String> allowed, final String where) {
    for (final String key : map.keySet()) {
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException(
            "Unknown option '" + key + "' in '" + where + "'; allowed: " + allowed);
      }
    }
  }

  private static long number(@Nullable final Object value, final String name, final long fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    throw new IllegalArgumentException("'" + name + "' must be a number");
  }

  private static <E extends Enum<E>> E parseEnum(
      @Nullable final Object value, final Class<E> type, final String name, final E fallback) {
    if (value == null) {
      return fallback;
    }
    for (final E candidate : type.getEnumConstants()) {
      if (candidate.name().equalsIgnoreCase(String.valueOf(value))) {
        return candidate;
      }
    }
    throw new IllegalArgumentException(
        "'engine."
            + name
            + "' must be one of "
            + java.util.Arrays.toString(type.getEnumConstants()));
  }
}
