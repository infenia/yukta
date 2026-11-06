// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.infenia.yukta.plugin.core.ConfigField;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Details of a supervisor type, with the settings it takes.
 *
 * @param type the type name
 * @param displayName the name shown to people
 * @param description what it does
 * @param usagePattern when to use it
 * @param category the catalog category
 * @param kind {@code SUPERVISOR}, {@code CONTROL_PLANE} or {@code COMPOSITE}
 * @param configSchema the settings of the type: name, type, required, default, options
 * @param commonSchema the settings every type takes
 * @param defaultAllowedCommands the commands allowed when the settings do not say; absent means
 *     every command
 * @param exampleSettings settings that pass validation
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores unmodifiable copies")
@JsonInclude(JsonInclude.Include.NON_NULL)
@SuppressWarnings("PMD.NullAssignment") // null is the documented value for "every command"
public record SupervisorTypeDetails(
    String type,
    String displayName,
    String description,
    String usagePattern,
    String category,
    String kind,
    List<ConfigField> configSchema,
    List<ConfigField> commonSchema,
    @Nullable List<String> defaultAllowedCommands,
    Map<String, Object> exampleSettings) {

  /** Keeps immutable copies. */
  public SupervisorTypeDetails {
    configSchema = List.copyOf(configSchema);
    commonSchema = List.copyOf(commonSchema);
    defaultAllowedCommands =
        defaultAllowedCommands == null ? null : List.copyOf(defaultAllowedCommands);
    exampleSettings = Map.copyOf(exampleSettings);
  }
}
