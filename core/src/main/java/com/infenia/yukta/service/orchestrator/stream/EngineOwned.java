// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * Takes back the message fields only the engine may write, on every edge a plugin's output crosses.
 *
 * <p>The engine owns: the source node and workflow id (stamped), {@code controlMessage} (a stream
 * message is never a control signal), the {@code attempt} header (set by the retry loop just before
 * a call) and the {@code failure.*} headers (set only on a dead letter, which travels on the error
 * port). A plugin may set them on its output, but they do not survive the next edge. The documented
 * list is in {@code docs/concepts/messages.mdx}.
 */
@UtilityClass
final class EngineOwned {

  /** Headers only the engine writes. */
  private static final String[] HEADERS = {
    NodePolicies.ATTEMPT_HEADER,
    NodePolicies.FAILURE_ATTEMPTS_HEADER,
    NodePolicies.FAILURE_EXCEPTION_HEADER,
    NodePolicies.FAILURE_MESSAGE_HEADER,
    NodePolicies.FAILURE_TIME_HEADER
  };

  /** Resets the engine-owned fields; the failure headers stay on a dead letter. */
  @SuppressWarnings("PMD.UseConcurrentHashMap") // local copy, never shared
  /* default */ static Message<?> reassert(final Message<?> msg) {
    final boolean deadLetter = NodePolicy.ERROR_PORT.equals(msg.sourcePort());
    final Map<String, Object> headers = new LinkedHashMap<>(msg.metadata());
    headers.remove(NodePolicies.ATTEMPT_HEADER);
    if (!deadLetter) {
      for (final String key : HEADERS) {
        headers.remove(key);
      }
    }
    return msg.withControl(false).withMetadata(headers);
  }
}
