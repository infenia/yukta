// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A ready-made set of settings for a supervisor type, which a person adjusts.
 *
 * @param name the preset name
 * @param description what it does
 * @param settings the settings to start from
 */
public record SupervisorPreset(String name, String description, Map<String, Object> settings) {

  /** Rejects missing parts and keeps an unmodifiable copy of the settings. */
  public SupervisorPreset {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
  }
}
