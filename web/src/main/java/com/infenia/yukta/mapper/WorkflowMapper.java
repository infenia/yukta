// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import com.infenia.yukta.dto.response.LogEntryResponse;
import com.infenia.yukta.dto.response.WorkflowGraphEdge;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for converting between core domain workflow models.
 *
 * <p>Note: WorkflowExecutionSummary and WorkflowProgress are core monitoring models used for
 * tracking workflow execution state. Controllers return these directly to clients via REST API.
 */
@Mapper(componentModel = "spring")
public interface WorkflowMapper {

  /**
   * Map a core workflow edge to its graph DTO.
   *
   * @param edge the core edge
   * @return the graph edge DTO
   */
  WorkflowGraphEdge toGraphEdge(WorkflowDefinition.Edge edge);

  /**
   * Map core workflow edges to their graph DTOs.
   *
   * @param edges the core edges
   * @return the graph edge DTOs
   */
  List<WorkflowGraphEdge> toGraphEdges(List<WorkflowDefinition.Edge> edges);

  /**
   * Map a plugin log entry to its streaming response DTO.
   *
   * @param entry the plugin log entry
   * @return the log entry response
   */
  @Mapping(target = "level", source = "logLevel")
  @Mapping(target = "stream", expression = "java(resolveStreamName(entry))")
  LogEntryResponse toLogEntry(PluginLogEntry entry);

  /**
   * Resolve the display stream name, folding custom stream names into the stream field.
   *
   * @param entry the plugin log entry
   * @return the resolved stream name, or null when the entry has no stream
   */
  default String resolveStreamName(final PluginLogEntry entry) {
    final LogStream stream = entry.stream();
    final String resolved;
    if (stream == null) {
      resolved = null;
    } else if (stream == LogStream.CUSTOM && entry.customStreamName() != null) {
      resolved = entry.customStreamName();
    } else {
      resolved = stream.toString();
    }
    return resolved;
  }
}
