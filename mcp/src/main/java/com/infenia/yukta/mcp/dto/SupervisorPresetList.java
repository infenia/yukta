// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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

  /**
   * One preset.
   *
   * @param name its name
   * @param description what it does
   * @param settings the settings to start from
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Preset(String name, String description, Map<String, Object> settings) {}
}
