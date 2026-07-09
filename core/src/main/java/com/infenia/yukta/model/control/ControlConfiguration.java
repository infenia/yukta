// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import java.util.Map;

/**
 * Standard configuration message for the Control Bus.
 *
 * @param nodeId the unique identifier of the node
 * @param config the node configuration map
 */
public record ControlConfiguration(String nodeId, Map<String, Object> config) {
  /** Compact constructor. */
  public ControlConfiguration {
    config = Map.copyOf(config);
  }
}
