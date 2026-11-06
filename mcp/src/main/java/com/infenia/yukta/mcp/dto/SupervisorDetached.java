// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The answer to detaching a supervisor.
 *
 * @param bindingId the binding that was removed
 * @param detached always true; an unknown binding is an error
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorDetached(String bindingId, boolean detached) {}
