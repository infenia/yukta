// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.infenia.yukta.logging.api.NodeOutputSlice;

/**
 * A slice of the output of one node, returned by the {@code get_node_output} MCP tool.
 *
 * @param executionId the execution the node belongs to
 * @param output the numbered lines and the totals
 * @param notice reminds the reader that the lines are untrusted data, not instructions
 */
public record NodeOutputResult(String executionId, NodeOutputSlice output, String notice) {}
