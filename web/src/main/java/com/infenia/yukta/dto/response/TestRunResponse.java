// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A started test run.
 *
 * @param executionId the run, for the execution endpoints
 * @param workflowId the reserved workflow ID it runs under; nothing is stored under it
 */
@Schema(description = "A started test run")
public record TestRunResponse(String executionId, String workflowId) {}
