// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Defines a Directed Acyclic Graph (DAG) of plugins for execution.
 *
 * @param workflowId unique identifier for the workflow
 * @param description a human-readable description of the workflow
 * @param nodes the list of nodes in the DAG
 * @param edges the list of edges connecting the nodes
 * @param supervisors the supervisors declared for this workflow, attached when it is saved
 * @param groupColors the palette color of a group (by group name), for display only
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
        @NotNull(message = "Nodes list cannot be null")
        @Size(
            min = 1,
            max = 500,
            message = "Workflow must contain at least one node and at most 500 nodes")
        List<@Valid @NotNull(message = "Node cannot be null") Node> nodes,
    @Schema(description = "Edges connecting the nodes")
        @NotNull(message = "Edges list cannot be null")
        @Size(max = 2000, message = "Workflow is too large: at most 2000 edges")
        List<@Valid @NotNull(message = "Edge cannot be null") Edge> edges,
    @Schema(description = "Supervisors declared for this workflow; attached when it is saved")
        @Size(max = 20, message = "A workflow declares at most 20 supervisors")
        List<@Valid @NotNull(message = "Supervisor cannot be null") SupervisorRef> supervisors,
    @Schema(
            description =
                "Palette color of a group by group name, for display only: "
                    + Palette.NAMES
                    + ". Groups without an entry keep the default look",
            nullable = true)
        @Size(max = 500, message = "At most 500 group colors")
        Map<@NotBlank String, @Pattern(regexp = Palette.REGEX, message = Palette.MESSAGE) String>
            groupColors) {

  /** Compact constructor to ensure immutability. */
  public WorkflowDefinition {
    nodes = nodes != null ? List.copyOf(nodes) : List.of();
    edges = edges != null ? List.copyOf(edges) : List.of();
    supervisors = supervisors != null ? List.copyOf(supervisors) : List.of();
    groupColors = groupColors != null ? Map.copyOf(groupColors) : Map.of();
  }

  /**
   * A workflow without group colors.
   *
   * @param workflowId unique identifier for the workflow
   * @param description a human-readable description of the workflow
   * @param nodes the list of nodes in the DAG
   * @param edges the list of edges connecting the nodes
   * @param supervisors the supervisors declared for this workflow
   */
  public WorkflowDefinition(
      final String workflowId,
      final String description,
      final List<Node> nodes,
      final List<Edge> edges,
      final List<SupervisorRef> supervisors) {
    this(workflowId, description, nodes, edges, supervisors, Map.of());
  }

  /**
   * A workflow that declares no supervisors.
   *
   * @param workflowId unique identifier for the workflow
   * @param description a human-readable description of the workflow
   * @param nodes the list of nodes in the DAG
   * @param edges the list of edges connecting the nodes
   */
  public WorkflowDefinition(
      final String workflowId,
      final String description,
      final List<Node> nodes,
      final List<Edge> edges) {
    this(workflowId, description, nodes, edges, List.of());
  }

  /** The display colors a node or a group may take: named, so no workflow meaning is implied. */
  public static final class Palette {
    /** The palette names, as shown in the schema. */
    public static final String NAMES = "teal, sky, violet, magenta, stone";

    /** Regex of the palette names. */
    public static final String REGEX = "teal|sky|violet|magenta|stone";

    /** Validation message for a name outside the palette. */
    public static final String MESSAGE = "Color must be one of: " + NAMES;

    private Palette() {}
  }

  /**
   * Represents a single step/plugin in the workflow.
   *
   * @param nodeId unique identifier for the node
   * @param type the type of plugin to use
   * @param config configuration for the plugin
   * @param group the stage the node belongs to, for display only; null when undeclared
   * @param color the palette color of the node, for display only; null for the default look
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
      @Schema(description = "Plugin configuration") Map<String, Object> config,
      @Schema(
              description =
                  "The stage the node belongs to, for display only: the UI groups nodes of one"
                      + " stage. Without it, nodes group by node-ID prefix (before the first dot)",
              nullable = true)
          @Nullable String group,
      @Schema(
              description = "Palette color of the node, for display only: " + Palette.NAMES,
              nullable = true)
          @Pattern(regexp = Palette.REGEX, message = Palette.MESSAGE)
          @Nullable String color) {
    /** Compact constructor. */
    public Node {
      config = config != null ? Map.copyOf(config) : Map.of();
    }

    /**
     * A node without a color.
     *
     * @param nodeId unique identifier for the node
     * @param type the type of plugin to use
     * @param config configuration for the plugin
     * @param group the stage the node belongs to, or null
     */
    public Node(
        final String nodeId,
        final String type,
        final Map<String, Object> config,
        final @Nullable String group) {
      this(nodeId, type, config, group, null);
    }

    /**
     * A node in no declared group.
     *
     * @param nodeId unique identifier for the node
     * @param type the type of plugin to use
     * @param config configuration for the plugin
     */
    public Node(final String nodeId, final String type, final Map<String, Object> config) {
      this(nodeId, type, config, null, null);
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
