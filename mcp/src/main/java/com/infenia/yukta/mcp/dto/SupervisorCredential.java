// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * A new credential for an agent supervisor. The token is shown once and passes through the model,
 * so prefer the UI or the CLI to make one.
 *
 * @param supervisorId the supervisor
 * @param token the secret
 * @param expiresAt when it stops working, ISO-8601, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorCredential(String supervisorId, String token, @Nullable String expiresAt) {}
