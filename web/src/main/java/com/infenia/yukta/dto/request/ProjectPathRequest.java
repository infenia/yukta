// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import com.infenia.yukta.validation.ProjectPath;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A working directory to look up on the server.
 *
 * @param projectPath the path, as a session would store it
 */
public record ProjectPathRequest(
    @Schema(description = "Working directory", example = "/data/pipelines/main") @ProjectPath
        String projectPath) {}
