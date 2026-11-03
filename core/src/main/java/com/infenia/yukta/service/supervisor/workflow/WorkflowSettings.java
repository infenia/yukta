// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The settings of a profile of type {@code workflow}, checked once.
 *
 * @param sessionId the internal session that holds the workflow
 * @param workflowId the workflow
 * @param events the event types (simple class names such as {@code InputRequested}) that start it
 * @param maxConcurrent how many runs of this supervisor may go at once
 * @param timeout how long one run may take
 */
public record WorkflowSettings(
    String sessionId, String workflowId, Set<String> events, int maxConcurrent, Duration timeout) {

  /** Default for {@code maxConcurrent}. */
  public static final int DEFAULT_CONCURRENT = 2;

  /** Default for {@code timeout}, in seconds. */
  public static final int DEFAULT_TIMEOUT_SECONDS = 60;

  /** Longest {@code timeout}, in seconds. */
  public static final int MAX_TIMEOUT_SECONDS = 600;

  /**
   * Keeps an immutable copy of the events.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param events the event types
   * @param maxConcurrent the concurrency
   * @param timeout the time limit
   */
  public WorkflowSettings {
    events = Set.copyOf(events);
  }

  /**
   * Reads and checks the settings.
   *
   * @param profile the profile
   * @param reservedSlots the size of the reserved pool, the ceiling of {@code maxConcurrent}
   * @return the settings
   * @throws IllegalArgumentException if a setting is missing or out of range
   */
  public static WorkflowSettings read(final SupervisorProfile profile, final int reservedSlots) {
    final Map<String, Object> m = profile.settings();
    final int concurrent = number(m.get("maxConcurrent"), "maxConcurrent", DEFAULT_CONCURRENT);
    if (concurrent < 1 || concurrent > reservedSlots) {
      throw new IllegalArgumentException(
          "Setting 'maxConcurrent' must be between 1 and the reserved slots ("
              + reservedSlots
              + ")");
    }
    final int seconds = number(m.get("timeout"), "timeout", DEFAULT_TIMEOUT_SECONDS);
    if (seconds < 1 || seconds > MAX_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "Setting 'timeout' is in seconds, 1 to " + MAX_TIMEOUT_SECONDS);
    }
    return new WorkflowSettings(
        text(m.get("sessionId"), "sessionId"),
        text(m.get("workflowId"), "workflowId"),
        events(m.get("events")),
        concurrent,
        Duration.ofSeconds(seconds));
  }

  private static Set<String> events(final Object value) {
    if (!(value instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("Setting 'events' must list event type names");
    }
    final List<String> names = new ArrayList<>();
    for (final Object item : list) {
      names.add(text(item, "events"));
    }
    return Set.copyOf(names);
  }

  private static String text(final Object value, final String name) {
    if (value instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalArgumentException("Setting '" + name + "' is required");
  }

  private static int number(final Object value, final String name, final int fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalArgumentException("Setting '" + name + "' must be a number");
  }
}
