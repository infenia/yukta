// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One setting as the running server sees it.
 *
 * @param key the property key that was asked for
 * @param value the value in use, {@code ******} for a secret, or {@code null} when nothing sets it
 *     and the built-in default applies
 * @param source where the value comes from: {@code argument}, {@code system property}, {@code
 *     environment variable}, {@code configuration file}, {@code other}, or {@code default} when
 *     nothing sets it
 */
@Schema(description = "One setting as the running server sees it")
public record ConfigValue(
    @Schema(description = "The property key that was asked for", example = "server.port")
        String key,
    @Schema(
            description =
                "The value in use, ****** for a secret, or null when nothing sets it and the"
                    + " built-in default applies",
            nullable = true)
        @Nullable String value,
    @Schema(
            description = "Where the value comes from, or `default` when nothing sets it",
            example = "environment variable")
        String source) {}
