// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The new size of the terminal of an agent.
 *
 * @param cols the width in columns, 1 to 1000
 * @param rows the height in rows, 1 to 1000
 */
@Schema(description = "The size of the terminal of an agent")
public record TerminalResizeRequest(
    @Schema(description = "Columns", example = "120") @Min(1) @Max(1000) int cols,
    @Schema(description = "Rows", example = "40") @Min(1) @Max(1000) int rows) {}
