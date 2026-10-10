// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

/** A checkpoint store that knows how many payload bytes it holds, for the metrics. */
@FunctionalInterface
public interface ByteCounted {

  /**
   * Payload bytes recorded and not yet cleared (sizes are estimates for non-text payloads).
   *
   * @return the byte count
   */
  long bytesHeld();
}
