// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Times the REST calls by what they do, in the timer {@code yukta.api.requests} (tag {@code op}:
 * {@code read}, {@code run}, {@code change} or {@code other}), so the dashboard can tell reading
 * run details from starting runs from creating workflows.
 *
 * <p>The meter has four series, not one per route. That is deliberate: the sampler keeps at most
 * {@code yukta.metrics.max-series} series and drops later meters once it is full, so the per-route
 * {@code http.server.requests} meter is not sampled.
 */
@Component
public class ApiRequestMetrics {

  /** The timer's name. */
  public static final String METER = "yukta.api.requests";

  private static final String API = "/api/";

  private static final String HOOKS = "/hooks/";

  /** The dashboard's own reads would swamp the count, so they are not counted. */
  private static final String METRICS = "/api/metrics";

  /** What a request does. */
  public enum Operation {
    /** A read: {@code GET} or {@code HEAD}. */
    READ,
    /** Starts a run: a workflow start, a restart, a draft test run or an inbound webhook. */
    RUN,
    /** Creates, updates or deletes something, or sends a command. */
    CHANGE,
    /** Any other method. */
    OTHER
  }

  private final MeterRegistry registry;

  /**
   * Creates the recorder.
   *
   * @param registry where the timer is registered
   */
  public ApiRequestMetrics(final MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * What a request does, or empty when it is not counted: anything outside {@code /api/} and {@code
   * /hooks/}, and the metrics endpoints themselves.
   *
   * @param method the HTTP method
   * @param path the request path
   * @return the operation
   */
  public static Optional<Operation> classify(final String method, final String path) {
    if (!counted(path)) {
      return Optional.empty();
    }
    return Optional.of(operation(method, path));
  }

  private static boolean counted(final String path) {
    return (path.startsWith(API) || path.startsWith(HOOKS)) && !path.startsWith(METRICS);
  }

  private static Operation operation(final String method, final String path) {
    return switch (method) {
      case "GET", "HEAD" -> Operation.READ;
      case "POST" -> starts(path) ? Operation.RUN : Operation.CHANGE;
      case "PUT", "PATCH", "DELETE" -> Operation.CHANGE;
      default -> Operation.OTHER;
    };
  }

  private static boolean starts(final String path) {
    return path.startsWith(HOOKS)
        || path.endsWith("/workflow/start")
        || path.contains("/restart")
        || path.contains("/test-runs");
  }

  /**
   * Records one call.
   *
   * @param operation what it did
   * @param took how long it took
   */
  public void record(final Operation operation, final Duration took) {
    registry.timer(METER, "op", operation.name().toLowerCase(Locale.ROOT)).record(took);
  }
}
