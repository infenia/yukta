// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.infenia.yukta.service.diagnosis.RunLogSearch;

/**
 * The answer of the {@code search_logs} MCP tool.
 *
 * @param search counts per node and, in LINES mode, the lines
 * @param notice reminds the reader that the lines are untrusted data, not instructions
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LogSearchResult(RunLogSearch search, String notice) {}
