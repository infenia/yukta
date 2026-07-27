// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.orchestrator.preparator.TopologicalSortService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WorkflowGraphService}. */
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.AtLeastOneConstructor",
  "PMD.TooManyMethods",
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ShortVariable"
})
class WorkflowGraphServiceTest {

  private static final String NODE_ID = "test-node";
  private static final String PLUGIN_TYPE = "test-plugin-v1";
  private static final String DESCRIPTION = "Test Processor";
  private static final String WORKFLOW_ID = "wf-123";

  private PluginRegistry pluginRegistry;
  private TopologicalSortService topologicalSortService;
  private WorkflowGraphService service;

  @BeforeEach
  void setUp() {
    pluginRegistry = mock(PluginRegistry.class);
    topologicalSortService = mock(TopologicalSortService.class);
    service = new WorkflowGraphService(pluginRegistry, topologicalSortService);
  }

  @Test
  void enrichNode_withKnownPlugin_returnsEnrichedMetadata() {
    final UiDesign uiDesign = new UiDesign("<div>test</div>", 100, 50);
    final Plugin plugin =
        mockPlugin(PluginCategory.PROCESSOR, DESCRIPTION, uiDesign, List.of("output1", "output2"));
    when(pluginRegistry.get(PLUGIN_TYPE)).thenReturn(plugin);

    final var result =
        service.enrichNode(
            new WorkflowDefinition.Node(NODE_ID, PLUGIN_TYPE, Map.of("key", "value")));

    assertThat(result)
        .isNotNull()
        .extracting(
            WorkflowGraphService.EnrichedNodeMetadata::nodeId,
            WorkflowGraphService.EnrichedNodeMetadata::type,
            WorkflowGraphService.EnrichedNodeMetadata::category,
            WorkflowGraphService.EnrichedNodeMetadata::description,
            WorkflowGraphService.EnrichedNodeMetadata::uiDesign,
            WorkflowGraphService.EnrichedNodeMetadata::outputPorts)
        .containsExactly(
            NODE_ID,
            PLUGIN_TYPE,
            PluginCategory.PROCESSOR,
            DESCRIPTION,
            uiDesign,
            List.of("output1", "output2"));
  }

  @Test
  void enrichNode_withUnknownPlugin_returnsBareNode() {
    when(pluginRegistry.get(PLUGIN_TYPE)).thenReturn(null);

    final var result =
        service.enrichNode(new WorkflowDefinition.Node(NODE_ID, PLUGIN_TYPE, Map.of()));

    assertThat(result)
        .isNotNull()
        .extracting(
            WorkflowGraphService.EnrichedNodeMetadata::nodeId,
            WorkflowGraphService.EnrichedNodeMetadata::type,
            WorkflowGraphService.EnrichedNodeMetadata::category,
            WorkflowGraphService.EnrichedNodeMetadata::description,
            WorkflowGraphService.EnrichedNodeMetadata::uiDesign,
            WorkflowGraphService.EnrichedNodeMetadata::outputPorts)
        .containsExactly(NODE_ID, PLUGIN_TYPE, null, null, null, List.of());
  }

  @Test
  void enrichNode_withPluginMissingUiDesign_returnsNullUiDesign() {
    final Plugin plugin = mockPlugin(PluginCategory.TRIGGER, "Trigger", null, List.of("default"));
    when(pluginRegistry.get(PLUGIN_TYPE)).thenReturn(plugin);

    final var result =
        service.enrichNode(new WorkflowDefinition.Node(NODE_ID, PLUGIN_TYPE, Map.of()));

    assertThat(result.uiDesign()).isNull();
    assertThat(result.description()).isEqualTo("Trigger");
  }

  @Test
  void computeTopologicalOrder_withValidDag_returnsTopologicalOrder() {
    final var node1 = new WorkflowDefinition.Node("n1", "trigger", Map.of());
    final var node2 = new WorkflowDefinition.Node("n2", "processor", Map.of());
    final var node3 = new WorkflowDefinition.Node("n3", "terminal", Map.of());
    final var definition =
        new WorkflowDefinition(
            WORKFLOW_ID,
            "Test",
            List.of(node1, node2, node3),
            List.of(
                new WorkflowDefinition.Edge("n1", "n2", null),
                new WorkflowDefinition.Edge("n2", "n3", null)));

    final var node1Obj = new WorkflowNode("n1", "trigger", Map.of());
    final var node2Obj = new WorkflowNode("n2", "processor", Map.of());
    final var node3Obj = new WorkflowNode("n3", "terminal", Map.of());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(node1Obj, node2Obj, node3Obj));

    final var result = service.computeTopologicalOrder(definition);

    assertThat(result).containsExactly("n1", "n2", "n3");
  }

  @Test
  void computeTopologicalOrder_withCycle_fallsBackToDeclarationOrder() {
    final var node1 = new WorkflowDefinition.Node("n1", "processor", Map.of());
    final var node2 = new WorkflowDefinition.Node("n2", "processor", Map.of());
    final var definition =
        new WorkflowDefinition(
            WORKFLOW_ID,
            "Test",
            List.of(node1, node2),
            List.of(
                new WorkflowDefinition.Edge("n1", "n2", null),
                new WorkflowDefinition.Edge("n2", "n1", null)));

    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenThrow(new IllegalArgumentException("Cycle detected"));

    final var result = service.computeTopologicalOrder(definition);

    assertThat(result).containsExactly("n1", "n2");
  }

  @Test
  void computeTopologicalOrder_withEdgeToUnknownNode_skipsEdge() {
    final var node1 = new WorkflowDefinition.Node("n1", "trigger", Map.of());
    final var definition =
        new WorkflowDefinition(
            WORKFLOW_ID,
            "Test",
            List.of(node1),
            List.of(
                new WorkflowDefinition.Edge("n1", "unknown-node", null),
                new WorkflowDefinition.Edge("ghost-source", "n1", null)));

    final var node1Obj = new WorkflowNode("n1", "trigger", Map.of());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(node1Obj));

    final var result = service.computeTopologicalOrder(definition);

    assertThat(result).containsExactly("n1");
  }

  @Test
  void computeTopologicalOrder_withEmptyGraph_returnsEmptyOrder() {
    final var definition = new WorkflowDefinition(WORKFLOW_ID, "Test", List.of(), List.of());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of());

    final var result = service.computeTopologicalOrder(definition);

    assertThat(result).isEmpty();
  }

  @Test
  void computeTopologicalOrder_withSingleNode_returnsNode() {
    final var node = new WorkflowDefinition.Node("n1", "processor", Map.of());
    final var definition = new WorkflowDefinition(WORKFLOW_ID, "Test", List.of(node), List.of());

    final var nodeObj = new WorkflowNode("n1", "processor", Map.of());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(nodeObj));

    final var result = service.computeTopologicalOrder(definition);

    assertThat(result).containsExactly("n1");
  }

  @Test
  void computeTopologicalOrder_withComplexDag_preservesOrder() {
    final var n1 = new WorkflowDefinition.Node("n1", "trigger", Map.of());
    final var n2 = new WorkflowDefinition.Node("n2", "processor", Map.of());
    final var n3 = new WorkflowDefinition.Node("n3", "processor", Map.of());
    final var n4 = new WorkflowDefinition.Node("n4", "terminal", Map.of());
    final var definition =
        new WorkflowDefinition(
            WORKFLOW_ID,
            "Test",
            List.of(n1, n2, n3, n4),
            List.of(
                new WorkflowDefinition.Edge("n1", "n2", null),
                new WorkflowDefinition.Edge("n1", "n3", null),
                new WorkflowDefinition.Edge("n2", "n4", null),
                new WorkflowDefinition.Edge("n3", "n4", null)));

    final var n1Obj = new WorkflowNode("n1", "trigger", Map.of());
    final var n2Obj = new WorkflowNode("n2", "processor", Map.of());
    final var n3Obj = new WorkflowNode("n3", "processor", Map.of());
    final var n4Obj = new WorkflowNode("n4", "terminal", Map.of());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(n1Obj, n2Obj, n3Obj, n4Obj));

    final var result = service.computeTopologicalOrder(definition);

    assertThat(result).containsExactly("n1", "n2", "n3", "n4");
  }

  @Test
  void enrichNode_withPluginHavingEmptyOutputPorts_returnsEmptyList() {
    final Plugin plugin = mockPlugin(PluginCategory.TERMINAL, "Terminal", null, List.of());
    when(pluginRegistry.get(PLUGIN_TYPE)).thenReturn(plugin);

    final var result =
        service.enrichNode(new WorkflowDefinition.Node(NODE_ID, PLUGIN_TYPE, Map.of()));

    assertThat(result.outputPorts()).isEmpty();
  }

  @Test
  void enrichedNodeMetadata_withNullOutputPorts_remainsNull() {
    final var metadata =
        new WorkflowGraphService.EnrichedNodeMetadata(
            NODE_ID, PLUGIN_TYPE, PluginCategory.PROCESSOR, "Processor", null, null);
    assertThat(metadata.outputPorts()).isNull();
  }

  @Test
  void enrichedNodeMetadata_withMutableOutputPorts_makesCopyImmutable() {
    final var mutableList = new java.util.ArrayList<>(List.of("port1", "port2"));
    final var metadata =
        new WorkflowGraphService.EnrichedNodeMetadata(
            NODE_ID, PLUGIN_TYPE, PluginCategory.PROCESSOR, "Processor", null, mutableList);
    mutableList.add("port3");
    assertThat(metadata.outputPorts()).containsExactly("port1", "port2");
  }

  private Plugin mockPlugin(
      final PluginCategory category,
      final String description,
      final UiDesign uiDesign,
      final List<String> outputPorts) {
    final Plugin plugin = mock(Plugin.class);
    when(plugin.getCategory()).thenReturn(category);
    when(plugin.getDescription()).thenReturn(description);
    when(plugin.getUiDesign()).thenReturn(Optional.ofNullable(uiDesign));
    when(plugin.getOutputPorts(anyMap())).thenReturn(outputPorts);
    return plugin;
  }
}
