// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Why a run failed, in a few kilobytes: the failed nodes of the run and of its failed sub-runs,
 * what the extractors recognised in their output, and the reads to make next. It is the first thing
 * to read about a run; the logs come after it, and only the parts it points to.
 *
 * @param executionId the run
 * @param workflowId its workflow
 * @param status its status
 * @param complete whether it has ended (a running run gets a digest of what failed so far)
 * @param runsSearched how many runs were looked at: the run and its failed sub-runs
 * @param failedNodes the nodes that failed or timed out, at most {@value #MAX_NODES}
 * @param findings the failures recognised in their output, tests and compile errors first
 * @param findingsOmitted how many findings were left out to stay within the size budget
 * @param next the reads that show more, cheapest first
 */
public record FailureDigest(
    String executionId,
    String workflowId,
    String status,
    boolean complete,
    int runsSearched,
    List<FailedNode> failedNodes,
    List<Finding> findings,
    int findingsOmitted,
    List<NextRead> next) {

  /** Most failed nodes listed. */
  public static final int MAX_NODES = 10;

  /** Keeps the lists as they were given. */
  public FailureDigest {
    failedNodes = List.copyOf(failedNodes);
    findings = List.copyOf(findings);
    next = List.copyOf(next);
  }

  /**
   * A node that failed or timed out.
   *
   * @param executionId its run
   * @param nodeId the node
   * @param module its plugin
   * @param status FAILURE or TIMED_OUT
   * @param error the reason the engine recorded, cut to {@value #MAX_ERROR} characters, or {@code
   *     null}
   * @param lines how many lines it wrote
   * @param errorLines how many of them were rated ERROR
   */
  public record FailedNode(
      String executionId,
      String nodeId,
      String module,
      String status,
      @Schema(nullable = true) @Nullable String error,
      long lines,
      long errorLines) {

    /** Longest error kept. */
    public static final int MAX_ERROR = 300;

    /** Cuts the error. */
    public FailedNode {
      error =
          error != null && error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) + "…" : error;
    }
  }

  /**
   * A read that shows more of the failure.
   *
   * @param kind {@code NODE_OUTPUT}: the lines of one node around {@code offset} (MCP {@code
   *     get_node_output}); {@code SEARCH}: a log search (MCP {@code search_logs})
   * @param executionId the run to read
   * @param nodeId the node, or {@code null} for every node
   * @param offset the first line to read (0-based, NODE_OUTPUT), or {@code null}
   * @param level the lowest level to search for (SEARCH), or {@code null}
   * @param why what the read shows
   */
  public record NextRead(
      Kind kind,
      String executionId,
      @Schema(nullable = true) @Nullable String nodeId,
      @Schema(nullable = true) @Nullable Long offset,
      @Schema(nullable = true) @Nullable String level,
      String why) {

    /** What kind of read. */
    public enum Kind {
      /** Read lines of one node. */
      NODE_OUTPUT,
      /** Search the logs. */
      SEARCH
    }
  }
}
