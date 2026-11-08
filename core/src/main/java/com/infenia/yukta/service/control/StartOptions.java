// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import com.infenia.yukta.plugin.gateway.SubWorkflowGateway;
import com.infenia.yukta.service.input.DefaultAnswerAuthorizer;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.runtime.ExecutionScheduler;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a privileged caller may ask of a start besides its payload: where it queues and which saved
 * version it runs. They travel in the payload under reserved keys (so a restart finds them); this
 * type is the only way in from outside, because {@link #withInitiator} drops both keys from a
 * caller's own payload.
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

  /**
   * The payload with the initiator under its reserved key. A caller's own value of that key is
   * dropped, so a run's initiator cannot be forged through the payload. The parent-run and depth
   * keys are dropped too, and so are the priority and version keys (see {@link StartOptions}): only
   * a sub-workflow node may name a parent, and it does not pass through here, so an external caller
   * cannot attach a run to another run's tree.
   *
   * @param payload the trigger payload
   * @param initiator who or what starts the run, or null if not known
   * @return a copy of the payload
   */
  public static Map<String, Object> withInitiator(
      final Map<String, Object> payload, @Nullable final String initiator) {
    final var copy = new HashMap<>(payload);
    copy.remove(ExecutionLogEvent.INITIATOR_KEY);
    copy.remove(SubWorkflowGateway.PARENT_KEY);
    copy.remove(SubWorkflowGateway.DEPTH_KEY);
    copy.remove(ExecutionScheduler.PRIORITY_KEY);
    copy.remove(VERSION_KEY);
    copy.remove(DefaultAnswerAuthorizer.DEPTH_KEY);
    copy.remove(DefaultAnswerAuthorizer.STARTER_KEY);
    if (initiator != null && !initiator.isBlank()) {
      copy.put(ExecutionLogEvent.INITIATOR_KEY, initiator);
    }
    return copy;
  }
}
