// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the server finds at a project path. Only these two facts: nothing about what is inside.
 *
 * @param exists something is at the path
 * @param directory it is a directory, as a project path must be
 */
public record ProjectPathCheck(
    @Schema(description = "Something is at the path") boolean exists,
    @Schema(description = "It is a directory") boolean directory) {}
