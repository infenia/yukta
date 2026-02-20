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
package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Defines a Directed Acyclic Graph (DAG) of plugins for execution.
 *
 * @param nodes the list of nodes in the DAG
 * @param edges the list of edges connecting the nodes
 */
@Schema(description = "Definition of a reactive workflow DAG")
public record WorkflowDefinition(
    @Schema(description = "Nodes in the workflow") @NotEmpty @Valid List<Node> nodes,
    @Schema(description = "Edges connecting the nodes") @NotNull @Valid List<Edge> edges) {

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
      @Schema(description = "Unique ID for the node") @NotBlank String nodeId,
      @Schema(description = "Plugin type") @NotBlank String type,
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
   */
  @Schema(description = "A connection between two nodes")
  public record Edge(
      @Schema(description = "Source node ID") @NotBlank String source,
      @Schema(description = "Target node ID") @NotBlank String target) {}
}
