// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import jakarta.annotation.Nullable;

/**
 * Metadata for an inbound parent edge to a node.
 *
 * <p>Encodes the source node, optional output port, and index in the Flux array for routing and
 * filtering incoming messages.
 *
 * @param parentIndex the index of the parent node's stream in the Flux array
 * @param sourceNodeId the ID of the source node
 * @param sourcePort optional output port filter; null means no port filtering
 * @param tolerateFailure true if the receiving node keeps going when this parent fails (join rule
 *     ALL_DONE) instead of failing with it
 */
public record ParentEdgeInfo(
    int parentIndex, String sourceNodeId, @Nullable String sourcePort, boolean tolerateFailure) {

  /**
   * Creates an edge whose parent's failure fails the receiving node.
   *
   * @param parentIndex the index of the parent node's stream in the Flux array
   * @param sourceNodeId the ID of the source node
   * @param sourcePort optional output port filter
   */
  public ParentEdgeInfo(
      final int parentIndex, final String sourceNodeId, @Nullable final String sourcePort) {
    this(parentIndex, sourceNodeId, sourcePort, false);
  }
}
