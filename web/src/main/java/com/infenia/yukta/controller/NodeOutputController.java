// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.NodeOutputPager;
import com.infenia.yukta.logging.api.NodeOutputSlice;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Reads part of what one node wrote, so a client (agent, UI, CLI, MCP) can ask for the last lines,
 * a page, or the lines that match, instead of streaming the whole log.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Node Output API", description = "Read part of the output of a node")
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public class NodeOutputController {

  /** The stored log lines. */
  private final PluginLogStore logStore;

  /** Session ownership check. */
  private final ExecutionAccess access;

  /**
   * Read a slice of a node's output.
   *
   * @param executionId the execution
   * @param nodeId the node
   * @param stream {@code stdout}, {@code stderr} or {@code all} (default)
   * @param tail the last N lines
   * @param head the first N lines
   * @param offset the first line of a page (0-based)
   * @param limit the page size (default 50, at most 500)
   * @param grep a regular expression the lines must match
   * @param context lines of context either side of a match (at most 20)
   * @return the slice with line numbers and the totals
   */
  @GetMapping("/executions/{executionId}/nodes/{nodeId}/output")
  @Operation(
      summary = "Read part of the output of a node",
      description =
          "Returns numbered lines of what the node wrote: the tail, the head, a page (offset and"
              + " limit, at most 500 lines) or only the lines matching a regular expression with"
              + " context. Reports the total lines and bytes and whether lines were left out. 400"
              + " for a bad argument, 404 for an unknown execution or node.")
  public Mono<ApiResponse<NodeOutputSlice>> getOutput(
      @Parameter(description = "Execution ID") @PathVariable final String executionId,
      @Parameter(description = "Node ID") @PathVariable final String nodeId,
      @Parameter(description = "stdout, stderr or all") @RequestParam(defaultValue = "all")
          final String stream,
      @Parameter(description = "The last N lines") @RequestParam(required = false)
          final Integer tail,
      @Parameter(description = "The first N lines") @RequestParam(required = false)
          final Integer head,
      @Parameter(description = "First line of a page, 0-based") @RequestParam(required = false)
          final Long offset,
      @Parameter(description = "Page size, default 50, at most 500") @RequestParam(required = false)
          final Integer limit,
      @Parameter(description = "Regular expression the lines must match")
          @RequestParam(required = false)
          final String grep,
      @Parameter(description = "Context lines either side of a match, at most 20")
          @RequestParam(required = false)
          final Integer context) {
    return Mono.defer(() -> Mono.just(streamName(stream)))
        .flatMap(
            which ->
                access
                    .require(null, executionId)
                    .filter(
                        progress ->
                            progress.tasks().stream()
                                .anyMatch(task -> nodeId.equals(task.nodeId())))
                    .switchIfEmpty(
                        Mono.error(() -> new NotFoundException("Node not found: " + nodeId)))
                    .flatMap(
                        _ ->
                            logStore
                                .readExecution(executionId)
                                .filter(
                                    entry ->
                                        nodeId.equals(entry.pluginId()) && inStream(entry, which))
                                .map(PluginLogEntry::message)
                                .collectList()
                                .map(
                                    lines ->
                                        ApiResponse.success(
                                            200,
                                            "Output retrieved",
                                            NodeOutputPager.slice(
                                                nodeId, which, lines, tail, head, offset, limit,
                                                grep, context)))));
  }

  private static String streamName(final String stream) {
    final String which = stream.toLowerCase(Locale.ROOT);
    if (!"all".equals(which) && !"stdout".equals(which) && !"stderr".equals(which)) {
      throw new IllegalArgumentException("stream must be stdout, stderr or all");
    }
    return which;
  }

  private static boolean inStream(final PluginLogEntry entry, final String which) {
    return "all".equals(which)
        || entry.stream() == ("stderr".equals(which) ? LogStream.STDERR : LogStream.STDOUT);
  }
}
