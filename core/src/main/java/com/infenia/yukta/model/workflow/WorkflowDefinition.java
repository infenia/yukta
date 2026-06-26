/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.model.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Defines a Directed Acyclic Graph (DAG) of plugins for execution.
 *
 * @param workflowId unique identifier for the workflow
 * @param description a human-readable description of the workflow
 * @param nodes the list of nodes in the DAG
 * @param edges the list of edges connecting the nodes
 */
@Schema(description = "Definition of a reactive workflow DAG")
public record WorkflowDefinition(
    @Schema(description = "Unique workflow identifier")
        @NotBlank(message = "Workflow ID is mandatory")
        String workflowId,
    @Schema(description = "A human-readable description of the workflow", example = "Quality check")
        @NotBlank(message = "Workflow description is mandatory")
        @Size(max = 256, message = "Workflow description must be at most 256 characters")
        String description,
    @Schema(description = "Nodes in the workflow")
        @NotEmpty(message = "Workflow must contain at least one node")
        List<@Valid @NotNull(message = "Node cannot be null") Node> nodes,
    @Schema(description = "Edges connecting the nodes")
        @NotNull(message = "Edges list cannot be null")
        List<@Valid @NotNull(message = "Edge cannot be null") Edge> edges) {

  /** Compact constructor to ensure immutability. */
  public WorkflowDefinition {
    nodes = nodes != null ? List.copyOf(nodes) : List.of();
    edges = edges != null ? List.copyOf(edges) : List.of();
  }

  /**
   * Represents a single step/plugin in the workflow.
   *
   * @param nodeId unique identifier for the node
   * @param type the type of plugin to use
   * @param config configuration for the plugin
   */
  @Schema(description = "A single node in the workflow DAG")
  public record Node(
      @Schema(description = "Unique ID for the node")
          @NotNull(message = "Node ID cannot be null")
          @NotBlank(message = "Node ID cannot be blank")
          String nodeId,
      @Schema(description = "Plugin type")
          @NotNull(message = "Plugin type cannot be null")
          @NotBlank(message = "Plugin type cannot be blank")
          String type,
      @Schema(description = "Plugin configuration") Map<String, Object> config) {
    /** Compact constructor. */
    public Node {
      config = config != null ? Map.copyOf(config) : Map.of();
    }
  }

  /**
   * Represents a connection between two nodes.
   *
   * @param source the source node ID
   * @param target the target node ID
   * @param sourcePort the optional source port; null means route all messages from source to target
   */
  @Schema(description = "A connection between two nodes")
  public record Edge(
      @Schema(description = "Source node ID")
          @NotNull(message = "Source node ID cannot be null")
          @NotBlank(message = "Source node ID cannot be blank")
          String source,
      @Schema(description = "Target node ID")
          @NotNull(message = "Target node ID cannot be null")
          @NotBlank(message = "Target node ID cannot be blank")
          String target,
      @Schema(description = "Source port name (optional; omit to route all messages)")
          String sourcePort) {}
}
