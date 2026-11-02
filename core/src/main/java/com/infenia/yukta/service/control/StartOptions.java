// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a privileged caller may ask of a start besides its payload: where it queues and which saved
 * version it runs. They travel in the payload under reserved keys (so a restart finds them); this
 * type is the only way in from outside, because {@code ExecutionLogEvent#withInitiator} drops both
 * keys from a caller's own payload.
 *
 * @param priority queue priority, higher first (default 0), or null
 * @param version saved workflow version to run instead of the current one, or null
 */
public record StartOptions(@Nullable Integer priority, @Nullable String version) {

  /** Reserved payload key that pins a run to a saved version of the workflow. */
  public static final String VERSION_KEY = "yukta.version";

  /**
   * Whether the caller asked for nothing.
   *
   * @return true if both fields are absent
   */
  public boolean isEmpty() {
    return priority == null && (version == null || version.isBlank());
  }

  /**
   * A copy of the payload with these options under their reserved keys.
   *
   * @param payload the payload, already cleaned of the reserved keys
   * @return the payload to start with
   */
  public Map<String, Object> into(final Map<String, Object> payload) {
    final var copy = new HashMap<>(payload);
    if (priority != null) {
      copy.put(ExecutionScheduler.PRIORITY_KEY, priority);
    }
    if (version != null && !version.isBlank()) {
      copy.put(VERSION_KEY, version.strip());
    }
    return copy;
  }
}
