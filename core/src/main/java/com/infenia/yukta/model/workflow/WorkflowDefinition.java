// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * @param admission how runs are admitted on top of the server's limits, or null for nothing more
 */
@Schema(description = "Definition of a reactive workflow DAG")
public record WorkflowDefinition(
    @Schema(description = "Unique workflow identifier")
        @NotBlank(message = "Workflow ID is mandatory")
        String workflowId,
    @Schema(
            description = "A human-readable description of the workflow",
            example = "Nightly report")
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
    @Schema(description = "What supervisors own in this workflow; assigned when it is saved")
        @JsonProperty("assignments")
        @JsonAlias("supervisors")
        @Size(max = 20, message = "A workflow declares at most 20 assignments")
        List<@Valid @NotNull(message = "Assignment cannot be null") SupervisorRef> supervisors,
    @Schema(
            description =
                "Palette color of a group by group name, for display only: "
                    + Palette.NAMES
                    + ". Groups without an entry keep the default look",
            nullable = true)
        @Size(max = 500, message = "At most 500 group colors")
        Map<@NotBlank String, @Pattern(regexp = Palette.REGEX, message = Palette.MESSAGE) String>
            groupColors,
    @Schema(description = "How runs are admitted on top of the server's limits", nullable = true)
        @Valid
        @Nullable WorkflowAdmission admission) {

  /** Compact constructor to ensure immutability. */
  public WorkflowDefinition {
    nodes = nodes != null ? List.copyOf(nodes) : List.of();
    edges = edges != null ? List.copyOf(edges) : List.of();
    supervisors = supervisors != null ? List.copyOf(supervisors) : List.of();
    groupColors = groupColors != null ? Map.copyOf(groupColors) : Map.of();
  }

  /**
   * A definition without admission settings.
   *
   * @param workflowId unique identifier for the workflow
   * @param description a human-readable description of the workflow
   * @param nodes the list of nodes in the DAG
   * @param edges the list of edges connecting the nodes
   * @param supervisors the supervisors declared for this workflow
   * @param groupColors the palette color of a group (by group name), for display only
   */
  public WorkflowDefinition(
      final String workflowId,
      final String description,
      final List<Node> nodes,
      final List<Edge> edges,
      final List<SupervisorRef> supervisors,
      final Map<String, String> groupColors) {
    this(workflowId, description, nodes, edges, supervisors, groupColors, null);
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
   * @param description what the node does, for display only; null shows the plugin description
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
          @Nullable String color,
      @Schema(
              description =
                  "Optional description of what this node does; the UI info button shows it."
                      + " Without it, the plugin description is shown",
              nullable = true)
          @Size(max = 1000, message = "Node description must be at most 1000 characters")
          @Nullable String description) {

    /** A node without a description. */
    public Node(
        final String nodeId,
        final String type,
        final Map<String, Object> config,
        final @Nullable String group,
        final @Nullable String color) {
      this(nodeId, type, config, group, color, null);
    }

    /** Compact constructor. */
    public Node {
      config = config != null ? Map.copyOf(config) : Map.of();
      description = nullIfBlank(description);
    }

    private static @Nullable String nullIfBlank(final @Nullable String value) {
      return value == null || value.isBlank() ? null : value;
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
      @Schema(
              description = "Source port name (optional; omit to route all messages)",
              nullable = true)
          @Nullable String sourcePort) {}
}
