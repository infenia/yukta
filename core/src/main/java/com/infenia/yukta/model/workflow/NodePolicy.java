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
 *   "retry":   { "maxAttempts": 3, "backoffMs": 200, "maxBackoffMs": 5000 },
 *   "onError": "ROUTE",
 *   "trigger": "ALL_DONE"
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@code retry} (processors): run the plugin again for the failing message, with exponential
 *       backoff and jitter. The plugin is then called once per message, so use it with per-message
 *       processors, not with ones that aggregate over the stream.
 *   <li>{@code onError} (processors): {@code FAIL} (default) fails the node; {@code CONTINUE} drops
 *       the failing message and carries on; {@code ROUTE} sends the failing message, tagged with
 *       its failure, to the node's {@code "error"} output port.
 *   <li>{@code trigger} (any node with several parents): {@code ALL_SUCCESS} (default) fails as
 *       soon as one parent fails; {@code ALL_DONE} keeps going on the other parents when one fails.
 * </ul>
 *
 * @param retry the retry policy, or null for none
 * @param onError what to do with a message that still fails
 * @param trigger how a failing parent affects this node
 */
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidDuplicateLiterals"})
public record NodePolicy(@Nullable Retry retry, OnError onError, JoinRule trigger) {

  /** Config key that holds the policy. */
  public static final String CONFIG_KEY = "engine";

  /** Output port that failing messages are routed to by {@link OnError#ROUTE}. */
  public static final String ERROR_PORT = "error";

  /** No retry, fail on error, fail when a parent fails. */
  public static final NodePolicy DEFAULT = new NodePolicy(null, OnError.FAIL, JoinRule.ALL_SUCCESS);

  /** Options accepted in the {@code engine} object. */
  private static final Set<String> KEYS = Set.of("retry", "onError", "trigger");

  /** Options accepted in the {@code retry} object. */
  private static final Set<String> RETRY_KEYS = Set.of("maxAttempts", "backoffMs", "maxBackoffMs");

  /** Upper bound for {@code maxAttempts}, so a typo cannot hammer a dependency. */
  private static final int MAX_ATTEMPTS_LIMIT = 10;

  /** Default delay before the first retry. */
  private static final long DEFAULT_BACKOFF_MS = 200;

  /** Default upper bound for the retry delay. */
  private static final long DEFAULT_MAX_BACKOFF_MS = 10_000;

  /** What to do with a message the plugin could not process. */
  public enum OnError {
    FAIL,
    CONTINUE,
    ROUTE
  }

  /** How a failing parent affects a node that has several parents. */
  public enum JoinRule {
    ALL_SUCCESS,
    ALL_DONE
  }

  /**
   * Retry settings.
   *
   * @param maxAttempts total attempts including the first one
   * @param backoff delay before the first retry; doubles on each retry
   * @param maxBackoff upper bound for the delay
   */
  public record Retry(int maxAttempts, Duration backoff, Duration maxBackoff) {}

  /**
   * Whether the plugin must be called once per message, which retry and onError need.
   *
   * @return true if retry or a non-default onError is set
   */
  public boolean perMessage() {
    return retry != null || onError != OnError.FAIL;
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
    return new NodePolicy(
        engine.containsKey("retry") ? parseRetry(asMap(engine.get("retry"), "retry")) : null,
        parseEnum(engine.get("onError"), OnError.class, "onError", OnError.FAIL),
        parseEnum(engine.get("trigger"), JoinRule.class, "trigger", JoinRule.ALL_SUCCESS));
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
    return new Retry((int) attempts, Duration.ofMillis(backoff), Duration.ofMillis(max));
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
