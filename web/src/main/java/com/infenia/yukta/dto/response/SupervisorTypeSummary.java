// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A supervisor type in the catalog.
 *
 * @param type the type name used when creating a supervisor
 * @param displayName the name shown to people
 * @param category the catalog category
 * @param kind {@code SUPERVISOR}, {@code CONTROL_PLANE} or {@code COMPOSITE}
 * @param icon the Material Symbols icon, or {@code null}
 */
@Schema(description = "A supervisor type in the catalog")
public record SupervisorTypeSummary(
    @Schema(description = "The type name") String type,
    @Schema(description = "The name shown to people") String displayName,
    @Schema(description = "The catalog category") String category,
    @Schema(description = "SUPERVISOR, CONTROL_PLANE or COMPOSITE") String kind,
    @Schema(description = "The icon, a Material Symbols name") String icon) {}
