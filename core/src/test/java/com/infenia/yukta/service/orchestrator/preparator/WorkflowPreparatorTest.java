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
package com.infenia.yukta.service.orchestrator.preparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.model.workflow.WorkflowEdge;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.orchestrator.compiler.WorkflowCompiler;
import com.infenia.yukta.service.orchestrator.validator.WorkflowValidator;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ShortVariable"
})
class WorkflowPreparatorTest {

  @Mock private PluginRegistry registry;
  @Mock private WorkflowValidator validator;
  @Mock private TopologicalSortService topologicalSortService;
  @Mock private WorkflowCompiler compiler;
  @Mock private Plugin plugin;
  @Mock private WorkflowTemplate template;

  private WorkflowPreparator preparator;

  @BeforeEach
  void setUp() {
    preparator = new WorkflowPreparator(registry, validator, topologicalSortService, compiler);
  }

  @Test
  void prepareWorkflow_singleNode_noEdges_returnsPreparedWorkflow() {
    final Node node = new Node("n1", "plugin-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf1", "desc", List.of(node), List.of());
    final WorkflowNode workflowNode = new WorkflowNode("n1", "plugin-type", Map.of());

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(Map.of())).thenReturn(Mono.empty());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(workflowNode));
    when(compiler.compileTemplate(anyList(), anyMap(), anyMap(), anyList())).thenReturn(template);

    StepVerifier.create(preparator.prepareWorkflow(def))
        .assertNext(
            pw -> {
              assertThat(pw.pluginCache()).containsKey("n1");
              assertThat(pw.pluginCache().get("n1")).isSameAs(plugin);
              assertThat(pw.topologicalOrder()).hasSize(1);
              assertThat(pw.edges()).isEmpty();
              assertThat(pw.template()).isSameAs(template);
            })
        .verifyComplete();

    verify(plugin).initialize(Map.of());
    verify(topologicalSortService).computeTopologicalOrder(anyList(), anyMap(), anyMap());
    verify(compiler).compileTemplate(anyList(), anyMap(), anyMap(), anyList());
  }

  @Test
  void prepareWorkflow_twoNodes_oneEdge_buildsAdjacencyAndParentsCorrectly() {
    final Node n1 = new Node("n1", "type-a", Map.of());
    final Node n2 = new Node("n2", "type-b", Map.of());
    final Edge edge = new Edge("n1", "n2", "default");
    final WorkflowDefinition def =
        new WorkflowDefinition("wf2", "two-node flow", List.of(n1, n2), List.of(edge));

    final WorkflowNode wn1 = new WorkflowNode("n1", "type-a", Map.of());
    final WorkflowNode wn2 = new WorkflowNode("n2", "type-b", Map.of());

    when(registry.get("type-a")).thenReturn(plugin);
    when(registry.get("type-b")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.empty());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(wn1, wn2));
    when(compiler.compileTemplate(anyList(), anyMap(), anyMap(), anyList())).thenReturn(template);

    StepVerifier.create(preparator.prepareWorkflow(def))
        .assertNext(
            pw -> {
              assertThat(pw.edges()).hasSize(1);
              final WorkflowEdge we = pw.edges().get(0);
              assertThat(we.source()).isEqualTo("n1");
              assertThat(we.target()).isEqualTo("n2");
              assertThat(we.sourcePort()).isEqualTo("default");
              assertThat(pw.adjacencyList().get("n1")).anyMatch(n -> "n2".equals(n.nodeId()));
              assertThat(pw.parentsList().get("n2")).anyMatch(n -> "n1".equals(n.nodeId()));
              assertThat(pw.pluginCache()).hasSize(2);
            })
        .verifyComplete();

    verify(plugin, times(2)).initialize(any());
  }

  @Test
  void prepareWorkflow_pluginNotFoundForNode_failsWithError() {
    final Node node = new Node("n1", "missing-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf3", "desc", List.of(node), List.of());

    when(registry.get("missing-type")).thenReturn(null);
    when(validator.validate(def)).thenReturn(Mono.empty());

    StepVerifier.create(preparator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Plugin not found: missing-type"))
        .verify();

    verify(plugin, never()).initialize(any());
  }

  @Test
  void prepareWorkflow_validationFails_propagatesError() {
    final Node node = new Node("n1", "plugin-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf4", "desc", List.of(node), List.of());

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def))
        .thenReturn(Mono.error(new IllegalArgumentException("validation failed")));

    StepVerifier.create(preparator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException && "validation failed".equals(e.getMessage()))
        .verify();

    verify(plugin, never()).initialize(any());
  }

  @Test
  void prepareWorkflow_pluginInitializeFails_shutdownIsInvokedAndErrorPropagated() {
    final Node node = new Node("n1", "plugin-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf5", "desc", List.of(node), List.of());

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.error(new RuntimeException("init failed")));
    when(plugin.shutdown(any())).thenReturn(Mono.empty());

    StepVerifier.create(preparator.prepareWorkflow(def))
        .expectErrorMatches(
            e -> e instanceof RuntimeException && "init failed".equals(e.getMessage()))
        .verify();

    verify(plugin).shutdown(Map.of());
  }

  @Test
  void prepareWorkflow_pluginShutdownFailsDuringCleanup_originalErrorStillPropagated() {
    final Node node = new Node("n1", "plugin-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf6", "desc", List.of(node), List.of());

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.error(new RuntimeException("init failed")));
    when(plugin.shutdown(any()))
        .thenReturn(Mono.error(new RuntimeException("shutdown also failed")));

    StepVerifier.create(preparator.prepareWorkflow(def))
        .expectErrorMatches(
            e -> e instanceof RuntimeException && "init failed".equals(e.getMessage()))
        .verify();
  }

  @Test
  void prepareWorkflow_pluginShutdownReturnsNull_handledGracefully() {
    final Node node = new Node("n1", "plugin-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf7", "desc", List.of(node), List.of());

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.error(new RuntimeException("init failed")));
    when(plugin.shutdown(any())).thenReturn(null);

    StepVerifier.create(preparator.prepareWorkflow(def))
        .expectErrorMatches(
            e -> e instanceof RuntimeException && "init failed".equals(e.getMessage()))
        .verify();
  }

  @Test
  void prepareWorkflow_oneOfTwoPluginsNotFound_failsWithError() {
    final Node n1 = new Node("n1", "type-a", Map.of());
    final Node n2 = new Node("n2", "missing-type", Map.of());
    final WorkflowDefinition def =
        new WorkflowDefinition("wf8", "desc", List.of(n1, n2), List.of());

    when(registry.get("type-a")).thenReturn(plugin);
    when(registry.get("missing-type")).thenReturn(null);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.empty());
    when(plugin.shutdown(any())).thenReturn(Mono.empty());

    StepVerifier.create(preparator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Plugin not found: missing-type"))
        .verify();
  }

  @Test
  void prepareWorkflow_nodeConfigPassedToInitialize() {
    final Map<String, Object> config = Map.of("key", "value");
    final Node node = new Node("n1", "plugin-type", config);
    final WorkflowDefinition def = new WorkflowDefinition("wf9", "desc", List.of(node), List.of());
    final WorkflowNode workflowNode = new WorkflowNode("n1", "plugin-type", config);

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(config)).thenReturn(Mono.empty());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(workflowNode));
    when(compiler.compileTemplate(anyList(), anyMap(), anyMap(), anyList())).thenReturn(template);

    StepVerifier.create(preparator.prepareWorkflow(def))
        .assertNext(pw -> assertThat(pw).isNotNull())
        .verifyComplete();

    verify(plugin).initialize(config);
  }

  @Test
  void prepareWorkflow_topologicalSortThrows_errorPropagatedAndShutdownInvoked() {
    final Node node = new Node("n1", "plugin-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf10", "desc", List.of(node), List.of());

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.empty());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenThrow(new IllegalArgumentException("cycle detected"));
    when(plugin.shutdown(any())).thenReturn(Mono.empty());

    StepVerifier.create(preparator.prepareWorkflow(def))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && "cycle detected".equals(e.getMessage()))
        .verify();

    verify(plugin).shutdown(Map.of());
  }

  @Test
  void prepareWorkflow_preparedWorkflowContainsCompilerTemplate() {
    final Node node = new Node("n1", "plugin-type", Map.of());
    final WorkflowDefinition def = new WorkflowDefinition("wf11", "desc", List.of(node), List.of());
    final WorkflowNode wn = new WorkflowNode("n1", "plugin-type", Map.of());

    when(registry.get("plugin-type")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.empty());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(wn));
    when(compiler.compileTemplate(anyList(), anyMap(), anyMap(), anyList())).thenReturn(template);

    StepVerifier.create(preparator.prepareWorkflow(def))
        .assertNext(pw -> assertThat(pw.template()).isSameAs(template))
        .verifyComplete();
  }

  @Test
  void prepareWorkflow_multipleEdges_allEdgesMappedCorrectly() {
    final Node n1 = new Node("n1", "type-a", Map.of());
    final Node n2 = new Node("n2", "type-b", Map.of());
    final Node n3 = new Node("n3", "type-c", Map.of());
    final Edge e1 = new Edge("n1", "n2", "port1");
    final Edge e2 = new Edge("n1", "n3", "port2");
    final WorkflowDefinition def =
        new WorkflowDefinition("wf12", "multi-edge", List.of(n1, n2, n3), List.of(e1, e2));

    final WorkflowNode wn1 = new WorkflowNode("n1", "type-a", Map.of());
    final WorkflowNode wn2 = new WorkflowNode("n2", "type-b", Map.of());
    final WorkflowNode wn3 = new WorkflowNode("n3", "type-c", Map.of());

    when(registry.get("type-a")).thenReturn(plugin);
    when(registry.get("type-b")).thenReturn(plugin);
    when(registry.get("type-c")).thenReturn(plugin);
    when(validator.validate(def)).thenReturn(Mono.empty());
    when(plugin.initialize(any())).thenReturn(Mono.empty());
    when(topologicalSortService.computeTopologicalOrder(anyList(), anyMap(), anyMap()))
        .thenReturn(List.of(wn1, wn2, wn3));
    when(compiler.compileTemplate(anyList(), anyMap(), anyMap(), anyList())).thenReturn(template);

    StepVerifier.create(preparator.prepareWorkflow(def))
        .assertNext(
            pw -> {
              assertThat(pw.edges()).hasSize(2);
              assertThat(pw.adjacencyList().get("n1")).hasSize(2);
              assertThat(pw.parentsList().get("n2")).hasSize(1);
              assertThat(pw.parentsList().get("n3")).hasSize(1);
            })
        .verifyComplete();
  }
}
