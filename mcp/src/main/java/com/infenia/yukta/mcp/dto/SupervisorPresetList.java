// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ready-made settings of a supervisor type.
 *
 * @param type the type
 * @param presets the presets
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorPresetList(String type, List<Preset> presets) {

  /** Keeps an immutable copy of the presets. */
  public SupervisorPresetList {
    presets = List.copyOf(presets);
  }

  /**
   * One preset.
   *
   * @param name its name
   * @param description what it does
   * @param settings the settings to start from
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Preset(String name, String description, Map<String, Object> settings) {

    /** Keeps an unmodifiable copy of the settings, which may hold nulls. */
    public Preset {
      settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }
  }
}
