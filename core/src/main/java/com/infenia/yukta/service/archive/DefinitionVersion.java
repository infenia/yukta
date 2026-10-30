// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.archive;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowEdge;
import com.infenia.yukta.model.workflow.WorkflowNode;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/** Content hash of a workflow graph, stable across JVM runs and independent of map ordering. */
@UtilityClass
@SuppressWarnings("PMD.ShortMethodName")
public class DefinitionVersion {

  /** Length of the hex prefix kept; 16 hex digits are plenty to tell versions apart. */
  private static final int HEX_LENGTH = 16;

  /**
   * Hashes the nodes (id, type, config) and edges of a graph.
   *
   * @param prepared the prepared workflow
   * @return 16 hex digits identifying the canonical form
   */
  public String of(final PreparedWorkflow prepared) {
    final String nodes =
        prepared.topologicalOrder().stream()
            .sorted(Comparator.comparing(WorkflowNode::nodeId))
            .map(n -> n.nodeId() + ":" + n.type() + ":" + canonical(n.config()))
            .collect(Collectors.joining(";"));
    final String edges =
        prepared.edges().stream()
            .sorted(
                Comparator.comparing(WorkflowEdge::source)
                    .thenComparing(WorkflowEdge::target)
                    .thenComparing(e -> String.valueOf(e.sourcePort())))
            .map(e -> e.source() + ">" + e.target() + ":" + e.sourcePort())
            .collect(Collectors.joining(";"));
    return hash(nodes + "|" + edges);
  }

  private String canonical(final Object value) {
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
          .map(e -> e.getKey() + "=" + canonical(e.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream()
          .map(DefinitionVersion::canonical)
          .collect(Collectors.joining(",", "[", "]"));
    }
    return String.valueOf(value);
  }

  /** Fingerprint of the content. Not security sensitive, so a name-based UUID (MD5) is enough. */
  private String hash(final String text) {
    final UUID uuid = UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    return uuid.toString().replace("-", "").substring(0, HEX_LENGTH);
  }
}
