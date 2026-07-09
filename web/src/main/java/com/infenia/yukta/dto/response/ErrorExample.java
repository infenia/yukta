// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Represents a common error example in session creation.
 *
 * @param error the error type or name
 * @param cause the cause of the error
 * @param resolution the resolution or fix for the error
 */
@Schema(description = "A common error example with cause and resolution")
public record ErrorExample(
    @Schema(description = "The error type or name") String error,
    @Schema(description = "The cause of the error") String cause,
    @Schema(description = "The resolution or fix for the error") String resolution) {}
