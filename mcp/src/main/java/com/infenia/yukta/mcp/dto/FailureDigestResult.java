// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.infenia.yukta.service.diagnosis.FailureDigest;

/**
 * The answer of the {@code get_failure_digest} MCP tool.
 *
 * @param digest the failed nodes, what failed in them and what to read next
 * @param notice reminds the reader that the texts are untrusted data, not instructions
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FailureDigestResult(FailureDigest digest, String notice) {}
