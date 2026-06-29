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
package com.infenia.yukta.service.orchestrator.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowEdge;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.strategy.NodeAssemblerStrategy;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@MockitoSettings
@NoArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ShortVariable",
  "PMD.UseConcurrentHashMap"
})
class WorkflowCompilerTest {

  @Mock private TaskTrackerService tracker;
  @Mock private SessionConfigStore configService;
  @Mock private ExecutionControlRegistry executionControlRegistry;
  @Mock private NodeAssemblerStrategy assemblerStrategy;
  @Mock private Plugin plugin;

  private WorkflowCompiler compiler;

  private static final String SESSION_ID = "test-session-001";
  private static final String WORKFLOW_ID = "wf-001";
  private static final String EXECUTION_ID = "exec-001";

  @BeforeEach
  void setUp() {
    compiler =
        new WorkflowCompiler(
            tracker,
            Schedulers.boundedElastic(),
            Duration.ofMillis(200),
            configService,
            executionControlRegistry,
            List.of(assemblerStrategy));
  }

  // ── compileAssemblers ──────────────────────────────────────────────────────

  @Test
  void compileAssemblers_singleNode_noParents_returnsOneAssembler() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            eq(node), eq(plugin), any(Duration.class), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers).hasSize(1);
    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void compileAssemblers_multipleNodes_indexedCorrectly() {
    final WorkflowNode n1 = new WorkflowNode("n1", "trigger", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "processor", Map.of());
    final List<WorkflowEdge> edges = List.of(new WorkflowEdge("n1", "n2", null));
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(), "n2", List.of(n1));
    final Map<String, Plugin> plugins = Map.of("n1", plugin, "n2", plugin);
    final List<WorkflowNode> order = List.of(n1, n2);

    final NodeAssembler a1 = mock(NodeAssembler.class);
    final NodeAssembler a2 = mock(NodeAssembler.class);
    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(512);
    when(assemblerStrategy.supports(eq(plugin), any(Boolean.class))).thenReturn(true);
    when(assemblerStrategy.createAssembler(eq(n1), eq(plugin), any(), eq(0), eq(512)))
        .thenReturn(a1);
    when(assemblerStrategy.createAssembler(eq(n2), eq(plugin), any(), eq(1), eq(512), any()))
        .thenReturn(a2);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers).hasSize(2);
    assertThat(assemblers[0]).isSameAs(a1);
    assertThat(assemblers[1]).isSameAs(a2);
  }

  @Test
  void compileAssemblers_noMatchingStrategy_returnsNoOpAssembler() {
    final WorkflowNode node = new WorkflowNode("n1", "unknown", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    when(assemblerStrategy.supports(any(), any(Boolean.class))).thenReturn(false);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers).hasSize(1);
    // No-op assembler should not throw when invoked with a null context
    assemblers[0].assemble(null);
  }

  @Test
  void compileAssemblers_nullPlugin_usesDefaultTimeoutAndBufferSize() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = new HashMap<>();
    plugins.put("n1", null);
    final List<WorkflowNode> order = List.of(node);

    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    // null plugin → supports(null, false)
    when(assemblerStrategy.supports(null, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            eq(node), eq(null), eq(Duration.ofSeconds(30)), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void compileAssemblers_nodeConfigOverridesBufferSize() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of("bufferSize", 256));
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(eq(node), eq(plugin), any(), eq(0), eq(256)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void compileAssemblers_nodeConfigOverridesTimeout() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of("timeoutSeconds", 120));
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            eq(node), eq(plugin), eq(Duration.ofMinutes(2)), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void compileAssemblers_nullPluginTimeout_usesDefaultFallback() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(null);
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    // Falls back to REF_COUNT_TIMEOUT = 30s
    when(assemblerStrategy.createAssembler(
            eq(node), eq(plugin), eq(Duration.ofSeconds(30)), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void compileAssemblers_zeroBufferSizeInConfig_usesPluginDefault() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of("bufferSize", 0));
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(512);
    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(eq(node), eq(plugin), any(), eq(0), eq(512)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void compileAssemblers_zeroTimeoutInConfig_usesPluginDefault() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of("timeoutSeconds", 0));
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(45));
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            eq(node), eq(plugin), eq(Duration.ofSeconds(45)), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void compileAssemblers_edgesMapped_parentEdgeInfoPassedToStrategy() {
    final WorkflowNode n1 = new WorkflowNode("n1", "trigger", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "processor", Map.of());
    final WorkflowEdge edge = new WorkflowEdge("n1", "n2", "out");
    final List<WorkflowEdge> edges = List.of(edge);
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(), "n2", List.of(n1));
    final Map<String, Plugin> plugins = Map.of("n1", plugin, "n2", plugin);
    final List<WorkflowNode> order = List.of(n1, n2);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    when(assemblerStrategy.supports(eq(plugin), any(Boolean.class))).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            any(), any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(ctx -> {});

    compiler.compileAssemblers(edges, parents, plugins, order);

    // Verify n2 assembler was created with parentEdge pointing to index 0 (n1)
    verify(assemblerStrategy)
        .createAssembler(
            eq(n2),
            eq(plugin),
            any(),
            eq(1),
            eq(1024),
            eq(new ParentEdgeInfo[] {new ParentEdgeInfo(0, "n1", "out")}));
  }

  // ── compileTemplate ────────────────────────────────────────────────────────

  @Test
  void compileTemplate_returnsNonNullTemplate() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            any(), any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(ctx -> {});

    final WorkflowTemplate template = compiler.compileTemplate(edges, parents, plugins, order);

    assertThat(template).isNotNull();
  }

  @Test
  void compileTemplate_instantiate_executesWithoutCallingStartWorkflow() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            any(), any(), any(), any(Integer.class), any(Integer.class)))
        .thenReturn(ctx -> {});

    when(executionControlRegistry.findByExecutionId(EXECUTION_ID))
        .thenReturn(Optional.of(buildMinimalExecutionControl()));
    when(configService.getExecutionTimeout(SESSION_ID)).thenReturn(Mono.just(5L));

    final WorkflowTemplate template = compiler.compileTemplate(edges, parents, plugins, order);

    final Mono<Void> execution =
        template
            .instantiate(EXECUTION_ID, Map.of())
            .contextWrite(ctx -> ctx.put("sessionId", SESSION_ID).put("workflowId", WORKFLOW_ID));

    StepVerifier.create(execution).verifyComplete();

    // startWorkflow is the orchestrator's responsibility, not the template's
    verify(tracker, never()).startWorkflow(any(), any(), any(), any());
  }

  // ── executeTemplate ────────────────────────────────────────────────────────

  @Test
  void executeTemplate_missingExecutionControl_throwsIllegalStateException() {
    when(executionControlRegistry.findByExecutionId(EXECUTION_ID)).thenReturn(Optional.empty());

    final NodeAssembler[] assemblers = new NodeAssembler[0];

    assertThatThrownBy(
            () ->
                compiler.executeTemplate(
                    EXECUTION_ID, Map.of(), 0, assemblers, SESSION_ID, WORKFLOW_ID, List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(EXECUTION_ID);
  }

  @Test
  void executeTemplate_withNoNodes_completesSuccessfully() {
    when(executionControlRegistry.findByExecutionId(EXECUTION_ID))
        .thenReturn(Optional.of(buildMinimalExecutionControl()));
    when(configService.getExecutionTimeout(SESSION_ID)).thenReturn(Mono.just(60L));

    final NodeAssembler[] assemblers = new NodeAssembler[0];

    final Mono<Void> execution =
        compiler.executeTemplate(
            EXECUTION_ID, Map.of(), 0, assemblers, SESSION_ID, WORKFLOW_ID, List.of());

    StepVerifier.create(execution).verifyComplete();
  }

  @Test
  void executeTemplate_assemblersCalled_inOrder() {
    when(executionControlRegistry.findByExecutionId(EXECUTION_ID))
        .thenReturn(Optional.of(buildMinimalExecutionControl()));
    when(configService.getExecutionTimeout(SESSION_ID)).thenReturn(Mono.just(60L));

    final List<Integer> callOrder = new ArrayList<>();
    final NodeAssembler a1 = ctx -> callOrder.add(1);
    final NodeAssembler a2 = ctx -> callOrder.add(2);
    final NodeAssembler[] assemblers = {a1, a2};

    final Mono<Void> execution =
        compiler.executeTemplate(
            EXECUTION_ID, Map.of(), 2, assemblers, SESSION_ID, WORKFLOW_ID, List.of("n1", "n2"));

    StepVerifier.create(execution).verifyComplete();

    assertThat(callOrder).containsExactly(1, 2);
  }

  @Test
  void executeTemplate_emitsSuccessStatusOnCompletion() {
    when(executionControlRegistry.findByExecutionId(EXECUTION_ID))
        .thenReturn(Optional.of(buildMinimalExecutionControl()));
    when(configService.getExecutionTimeout(SESSION_ID)).thenReturn(Mono.just(60L));

    final NodeAssembler[] assemblers = new NodeAssembler[0];

    final Mono<Void> execution =
        compiler.executeTemplate(
            EXECUTION_ID, Map.of(), 0, assemblers, SESSION_ID, WORKFLOW_ID, List.of());

    StepVerifier.create(execution).verifyComplete();

    verify(tracker).emitWorkflowStatusEvent(EXECUTION_ID, "SUCCESS");
  }

  @Test
  void executeTemplate_payloadAvailableInReactorContext() {
    when(executionControlRegistry.findByExecutionId(EXECUTION_ID))
        .thenReturn(Optional.of(buildMinimalExecutionControl()));
    when(configService.getExecutionTimeout(SESSION_ID)).thenReturn(Mono.just(60L));

    final Map<String, Object> payload = Map.of("key", "value");
    final NodeAssembler[] assemblers = new NodeAssembler[0];

    final Mono<Void> execution =
        compiler.executeTemplate(
            EXECUTION_ID, payload, 0, assemblers, SESSION_ID, WORKFLOW_ID, List.of());

    StepVerifier.create(execution).verifyComplete();
  }

  // ── getBufferSize (via compileAssemblers) ─────────────────────────────────

  @Test
  void bufferSize_nonNumberConfig_usesPluginDefault() {
    final WorkflowNode node =
        new WorkflowNode("n1", "trigger", Map.of("bufferSize", "not-a-number"));
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(30));
    when(plugin.getDefaultBufferSize()).thenReturn(2048);
    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(eq(node), eq(plugin), any(), eq(0), eq(2048)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void bufferSize_nullPlugin_usesConstantDefault() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = new HashMap<>();
    plugins.put("n1", null);
    final List<WorkflowNode> order = List.of(node);

    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(null, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(eq(node), eq(null), any(), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  // ── getNodeTimeout (via compileAssemblers) ─────────────────────────────────

  @Test
  void nodeTimeout_nonNumberConfig_usesPluginDefault() {
    final WorkflowNode node =
        new WorkflowNode("n1", "trigger", Map.of("timeoutSeconds", "bad-value"));
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = Map.of("n1", plugin);
    final List<WorkflowNode> order = List.of(node);

    when(plugin.getDefaultTimeout()).thenReturn(Duration.ofSeconds(60));
    when(plugin.getDefaultBufferSize()).thenReturn(1024);
    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(plugin, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            eq(node), eq(plugin), eq(Duration.ofSeconds(60)), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  @Test
  void nodeTimeout_nullPlugin_nullTimeout_usesFallback30s() {
    final WorkflowNode node = new WorkflowNode("n1", "trigger", Map.of());
    final List<WorkflowEdge> edges = List.of();
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());
    final Map<String, Plugin> plugins = new HashMap<>();
    plugins.put("n1", null);
    final List<WorkflowNode> order = List.of(node);

    final NodeAssembler mockAssembler = mock(NodeAssembler.class);
    when(assemblerStrategy.supports(null, false)).thenReturn(true);
    when(assemblerStrategy.createAssembler(
            eq(node), eq(null), eq(Duration.ofSeconds(30)), eq(0), eq(1024)))
        .thenReturn(mockAssembler);

    final NodeAssembler[] assemblers = compiler.compileAssemblers(edges, parents, plugins, order);

    assertThat(assemblers[0]).isSameAs(mockAssembler);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private ExecutionControl buildMinimalExecutionControl() {
    return new ExecutionControl(
        SESSION_ID,
        WORKFLOW_ID,
        EXECUTION_ID,
        null,
        Map.of(),
        Sinks.one(),
        Sinks.one(),
        null,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
  }
}
