// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.UiDesign;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Details of a supervisor type.
 *
 * @param type the type name
 * @param displayName the name shown to people
 * @param description what it does
 * @param usagePattern when to use it
 * @param category the catalog category
 * @param kind {@code SUPERVISOR}, {@code CONTROL_PLANE} or {@code COMPOSITE}
 * @param uiDesign the icon and colour, or {@code null}
 * @param configSchema the settings of the type
 * @param commonSchema the settings every type takes
 * @param defaultAllowedCommands the commands allowed when the settings do not say; {@code null}
 *     means every command
 * @param exampleSettings settings that pass validation
 */
@Schema(description = "Details of a supervisor type")
public record SupervisorTypeDetails(
    String type,
    String displayName,
    String description,
    String usagePattern,
    String category,
    String kind,
    UiDesign uiDesign,
    List<ConfigField> configSchema,
    List<ConfigField> commonSchema,
    @Schema(description = "Commands allowed by default; null means every command")
        List<String> defaultAllowedCommands,
    Map<String, Object> exampleSettings) {}
