// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import java.util.Collection;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Shortens message payloads for the live log so large payloads are not echoed in full. */
@UtilityClass
public class LivePayloadPreview {

  /** A collection or map larger than this is described, not printed. */
  private static final int MAX_LOG_ELEMENTS = 256;

  /** Maximum number of payload characters echoed to the live log per message. */
  private static final int MAX_LOG_PAYLOAD_SIZE = 2_048;

  /**
   * Bounds the payload echoed into the live log so large payloads are not copied once per node.
   *
   * @param payload the stringified payload
   * @return the payload, cut to {@code MAX_LOG_PAYLOAD_SIZE} characters with a marker if longer
   */
  public static String preview(final Object payload) {
    return switch (payload) {
      case CharSequence text when text.length() > MAX_LOG_PAYLOAD_SIZE ->
          text.subSequence(0, MAX_LOG_PAYLOAD_SIZE)
              + "... [truncated "
              + (text.length() - MAX_LOG_PAYLOAD_SIZE)
              + " chars]";
      case byte[] bytes -> "<" + bytes.length + " bytes>";
      case Collection<?> items when items.size() > MAX_LOG_ELEMENTS ->
          payload.getClass().getSimpleName() + " of " + items.size() + " elements";
      case Map<?, ?> entries when entries.size() > MAX_LOG_ELEMENTS ->
          payload.getClass().getSimpleName() + " of " + entries.size() + " entries";
      case null -> "null";
      default -> truncateForLog(String.valueOf(payload));
    };
  }

  private static String truncateForLog(final String payload) {
    return payload.length() <= MAX_LOG_PAYLOAD_SIZE
        ? payload
        : payload.substring(0, MAX_LOG_PAYLOAD_SIZE)
            + "... [truncated "
            + (payload.length() - MAX_LOG_PAYLOAD_SIZE)
            + " chars]";
  }
}
