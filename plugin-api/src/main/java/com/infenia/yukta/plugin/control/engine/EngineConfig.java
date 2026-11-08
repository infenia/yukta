// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import java.util.Map;

/**
 * The settings of an engine, held by the supervisor.
 *
 * @param settings the settings the factory's schema describes
 */
public record EngineConfig(Map<String, Object> settings) {

  /** Keeps an unmodifiable copy; a setting may be null. */
  public EngineConfig {
    settings =
        settings == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(settings));
  }
}
