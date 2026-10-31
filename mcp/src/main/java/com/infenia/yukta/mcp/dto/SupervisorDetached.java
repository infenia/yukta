// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

/**
 * The answer to detaching a supervisor.
 *
 * @param bindingId the binding that was removed
 * @param detached always true; an unknown binding is an error
 */
public record SupervisorDetached(String bindingId, boolean detached) {}
