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
package com.infenia.yukta.service.orchestrator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.factory.ExecutionControlFactory;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.compiler.WorkflowCompiler;
import com.infenia.yukta.service.orchestrator.preparator.WorkflowPreparator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LambdaCanBeMethodReference",
  "PMD.UseShortArrayInitializer"
})
class WorkflowOrchestratorTest {

  @Mock private TaskTrackerService tracker;
  @Mock private ExecutionControlRegistry executionControlRegistry;
  @Mock private ExecutionControlFactory executionControlFactory;
  @Mock private NodeCheckpointStore checkpointStore;
  @Mock private WorkflowCompiler compiler;
  @Mock private WorkflowPreparator preparator;
  @Mock private ExecutionControl executionControl;

  private WorkflowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orchestrator =
        new WorkflowOrchestrator(
            tracker,
            executionControlRegistry,
            executionControlFactory,
            checkpointStore,
            compiler,
            preparator);
  }

  // --- prepareWorkflow ---

  @Test
  void prepareWorkflow_delegatesToPreparator() {
    final WorkflowDefinition def = buildMinimalDefinition("wf-1");
    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-a"));
    when(preparator.prepareWorkflow(def)).thenReturn(Mono.just(prepared));

    StepVerifier.create(orchestrator.prepareWorkflow(def)).expectNext(prepared).verifyComplete();

    verify(preparator).prepareWorkflow(def);
  }

  @Test
  void prepareWorkflow_propagatesPreparatorError() {
    final WorkflowDefinition def = buildMinimalDefinition("wf-err");
    when(preparator.prepareWorkflow(def))
        .thenReturn(Mono.error(new IllegalArgumentException("bad def")));

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  // --- execute ---

  @Test
  void execute_registersControl_startsWorkflow_andUnregistersOnCompletion() {
    final String sessionId = "sess-1";
    final String workflowId = "wf-1";
    final String executionId = "exec-1";
    final Map<String, Object> payload = Map.of("key", "val");

    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-a"));
    final Sinks.One<Void> immediateStop = Sinks.one();
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(
        sessionId, workflowId, executionId, prepared, payload, immediateStop, safeStop);
    when(tracker.startWorkflow(eq(executionId), eq(sessionId), eq(workflowId), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(eq(executionId), eq(payload))).thenReturn(Mono.empty());

    StepVerifier.create(orchestrator.execute(sessionId, workflowId, executionId, prepared, payload))
        .verifyComplete();

    verify(executionControlFactory).create(sessionId, workflowId, executionId, prepared, payload);
    verify(executionControlRegistry).register(executionControl);
    verify(tracker).startWorkflow(executionId, sessionId, workflowId, List.of("node-a"));
    verify(executionControlRegistry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
  }

  @Test
  void execute_unregistersAndClearsCheckpointOnError() {
    final String sessionId = "sess-err";
    final String workflowId = "wf-err";
    final String executionId = "exec-err";
    final Map<String, Object> payload = Map.of("k", "v");

    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-x"));
    final Sinks.One<Void> immediateStop = Sinks.one();
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(
        sessionId, workflowId, executionId, prepared, payload, immediateStop, safeStop);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(anyString(), any()))
        .thenReturn(Mono.error(new RuntimeException("execution failed")));

    StepVerifier.create(orchestrator.execute(sessionId, workflowId, executionId, prepared, payload))
        .expectError(RuntimeException.class)
        .verify();

    verify(executionControlRegistry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
  }

  @Test
  void execute_immediateStopSinkTerminatesExecution() {
    final String sessionId = "sess-stop";
    final String workflowId = "wf-stop";
    final String executionId = "exec-stop";
    final Map<String, Object> payload = Map.of();

    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-s"));
    final Sinks.One<Void> immediateStop = Sinks.one();
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(
        sessionId, workflowId, executionId, prepared, payload, immediateStop, safeStop);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    // Template never completes — stopped via immediateStop signal
    when(prepared.template().instantiate(anyString(), any())).thenReturn(Mono.never());

    final Mono<Void> execution =
        orchestrator.execute(sessionId, workflowId, executionId, prepared, payload);

    StepVerifier.create(execution).then(() -> immediateStop.tryEmitEmpty()).verifyComplete();

    verify(executionControlRegistry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
  }

  @Test
  void execute_safeStopSinkTerminatesExecution() {
    final String sessionId = "sess-safe";
    final String workflowId = "wf-safe";
    final String executionId = "exec-safe";
    final Map<String, Object> payload = Map.of();

    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-q"));
    final Sinks.One<Void> immediateStop = Sinks.one();
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(
        sessionId, workflowId, executionId, prepared, payload, immediateStop, safeStop);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(anyString(), any())).thenReturn(Mono.never());

    final Mono<Void> execution =
        orchestrator.execute(sessionId, workflowId, executionId, prepared, payload);

    StepVerifier.create(execution).then(() -> safeStop.tryEmitEmpty()).verifyComplete();

    verify(executionControlRegistry).unregister(executionId);
    verify(checkpointStore).clear(executionId);
  }

  @Test
  void execute_tracksAllNodeIds() {
    final String sessionId = "sess-nodes";
    final String workflowId = "wf-nodes";
    final String executionId = "exec-nodes";
    final Map<String, Object> payload = Map.of("p", 1);

    final PreparedWorkflow prepared =
        buildPreparedWorkflow(List.of("trigger", "processor", "terminal"));
    final Sinks.One<Void> immediateStop = Sinks.one();
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(
        sessionId, workflowId, executionId, prepared, payload, immediateStop, safeStop);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(anyString(), any())).thenReturn(Mono.empty());

    StepVerifier.create(orchestrator.execute(sessionId, workflowId, executionId, prepared, payload))
        .verifyComplete();

    verify(tracker)
        .startWorkflow(
            executionId, sessionId, workflowId, List.of("trigger", "processor", "terminal"));
  }

  // --- restartFromNode ---

  @Test
  void restartFromNode_bypassesPreRestartNodes_andRunsFromRestartNode() {
    final String sessionId = "sess-restart";
    final String workflowId = "wf-restart";
    final String prevExecId = "exec-prev";
    final String newExecId = "exec-new";

    final WorkflowNode trigger = new WorkflowNode("trigger", "trigger-type", Map.of());
    final WorkflowNode processor = new WorkflowNode("processor", "proc-type", Map.of());
    final PreparedWorkflow prepared = buildPreparedWorkflowFromNodes(List.of(trigger, processor));

    final NodeAssembler[] assemblers = new NodeAssembler[] {ctx -> {}, ctx -> {}};
    when(compiler.compileAssemblers(any(), any(), any(), any())).thenReturn(assemblers);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(compiler.executeTemplate(
            anyString(), any(), eq(2), any(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());

    final Sinks.One<Void> safeStop = Sinks.one();
    setupRestartControl(sessionId, workflowId, newExecId, prepared, safeStop);

    StepVerifier.create(
            orchestrator.restartFromNode(
                sessionId, workflowId, prevExecId, newExecId, prepared, "processor", Map.of()))
        .verifyComplete();

    verify(compiler).compileAssemblers(any(), any(), any(), any());
    verify(compiler)
        .executeTemplate(
            eq(newExecId), any(), eq(2), any(), eq(sessionId), eq(workflowId), anyList());
    verify(executionControlRegistry).unregister(newExecId);
    verify(checkpointStore).clear(newExecId);
  }

  @Test
  void restartFromNode_withCheckpointForParent_replacesPreRestartAssemblerWithCheckpointReplay() {
    final String sessionId = "sess-checkpoint";
    final String workflowId = "wf-checkpoint";
    final String prevExecId = "exec-cp-prev";
    final String newExecId = "exec-cp-new";
    final String restartNodeId = "processor";

    final WorkflowNode trigger = new WorkflowNode("trigger", "trigger-type", Map.of());
    final WorkflowNode processor = new WorkflowNode("processor", "proc-type", Map.of());
    final PreparedWorkflow prepared = buildPreparedWorkflowFromNodes(List.of(trigger, processor));

    final NodeAssembler[] assemblers = new NodeAssembler[] {ctx -> {}, ctx -> {}};
    when(compiler.compileAssemblers(any(), any(), any(), any())).thenReturn(assemblers);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(compiler.executeTemplate(
            anyString(), any(), eq(2), any(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());

    final Sinks.One<Void> safeStop = Sinks.one();
    setupRestartControl(sessionId, workflowId, newExecId, prepared, safeStop);

    @SuppressWarnings("unchecked")
    final Message<String> checkpoint = (Message<String>) org.mockito.Mockito.mock(Message.class);

    StepVerifier.create(
            orchestrator.restartFromNode(
                sessionId,
                workflowId,
                prevExecId,
                newExecId,
                prepared,
                restartNodeId,
                Map.of("trigger", checkpoint)))
        .verifyComplete();

    verify(compiler).compileAssemblers(any(), any(), any(), any());
  }

  @Test
  void restartFromNode_unregistersAndClearsCheckpointOnError() {
    final String sessionId = "sess-restart-err";
    final String workflowId = "wf-restart-err";
    final String prevExecId = "exec-r-prev";
    final String newExecId = "exec-r-new";

    final WorkflowNode node = new WorkflowNode("trigger", "trigger-type", Map.of());
    final PreparedWorkflow prepared = buildPreparedWorkflowFromNodes(List.of(node));

    final NodeAssembler[] assemblers = new NodeAssembler[] {ctx -> {}};
    when(compiler.compileAssemblers(any(), any(), any(), any())).thenReturn(assemblers);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(compiler.executeTemplate(
            anyString(), any(), eq(1), any(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.error(new RuntimeException("restart execution failed")));

    final Sinks.One<Void> safeStop = Sinks.one();
    setupRestartControl(sessionId, workflowId, newExecId, prepared, safeStop);

    StepVerifier.create(
            orchestrator.restartFromNode(
                sessionId, workflowId, prevExecId, newExecId, prepared, "trigger", Map.of()))
        .expectError(RuntimeException.class)
        .verify();

    verify(executionControlRegistry).unregister(newExecId);
    verify(checkpointStore).clear(newExecId);
  }

  @Test
  void restartFromNode_safeStopSinkTerminatesExecution() {
    final String sessionId = "sess-rs-stop";
    final String workflowId = "wf-rs-stop";
    final String prevExecId = "exec-rs-prev";
    final String newExecId = "exec-rs-new";

    final WorkflowNode node = new WorkflowNode("trigger", "t", Map.of());
    final PreparedWorkflow prepared = buildPreparedWorkflowFromNodes(List.of(node));

    final NodeAssembler[] assemblers = new NodeAssembler[] {ctx -> {}};
    when(compiler.compileAssemblers(any(), any(), any(), any())).thenReturn(assemblers);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(compiler.executeTemplate(
            anyString(), any(), eq(1), any(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.never());

    final Sinks.One<Void> safeStop = Sinks.one();
    setupRestartControl(sessionId, workflowId, newExecId, prepared, safeStop);

    final Mono<Void> restart =
        orchestrator.restartFromNode(
            sessionId, workflowId, prevExecId, newExecId, prepared, "trigger", Map.of());

    StepVerifier.create(restart).then(() -> safeStop.tryEmitEmpty()).verifyComplete();

    verify(executionControlRegistry).unregister(newExecId);
    verify(checkpointStore).clear(newExecId);
  }

  @Test
  void restartFromNode_withUnknownRestartNode_defaultsToFirstPosition() {
    final String sessionId = "sess-unknown-node";
    final String workflowId = "wf-unknown-node";
    final String prevExecId = "exec-un-prev";
    final String newExecId = "exec-un-new";

    final WorkflowNode trigger = new WorkflowNode("trigger", "t", Map.of());
    final WorkflowNode processor = new WorkflowNode("processor", "p", Map.of());
    final PreparedWorkflow prepared = buildPreparedWorkflowFromNodes(List.of(trigger, processor));

    final NodeAssembler[] assemblers = new NodeAssembler[] {ctx -> {}, ctx -> {}};
    when(compiler.compileAssemblers(any(), any(), any(), any())).thenReturn(assemblers);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(compiler.executeTemplate(
            anyString(), any(), eq(2), any(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());

    final Sinks.One<Void> safeStop = Sinks.one();
    setupRestartControl(sessionId, workflowId, newExecId, prepared, safeStop);

    // "nonexistent" defaults to index 0 → no pre-restart nodes are replaced
    StepVerifier.create(
            orchestrator.restartFromNode(
                sessionId, workflowId, prevExecId, newExecId, prepared, "nonexistent", Map.of()))
        .verifyComplete();

    verify(compiler).compileAssemblers(any(), any(), any(), any());
  }

  @Test
  void restartFromNode_withNoCheckpointForParent_replacesPreRestartAssemblerWithEmpty() {
    final String sessionId = "sess-no-cp";
    final String workflowId = "wf-no-cp";
    final String prevExecId = "exec-no-cp-prev";
    final String newExecId = "exec-no-cp-new";

    final WorkflowNode trigger = new WorkflowNode("trigger", "t", Map.of());
    final WorkflowNode processor = new WorkflowNode("processor", "p", Map.of());
    final WorkflowNode terminal = new WorkflowNode("terminal", "term", Map.of());
    final PreparedWorkflow prepared =
        buildPreparedWorkflowFromNodes(List.of(trigger, processor, terminal));

    final NodeAssembler[] assemblers = new NodeAssembler[] {ctx -> {}, ctx -> {}, ctx -> {}};
    when(compiler.compileAssemblers(any(), any(), any(), any())).thenReturn(assemblers);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(compiler.executeTemplate(
            anyString(), any(), eq(3), any(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());

    final Sinks.One<Void> safeStop = Sinks.one();
    setupRestartControl(sessionId, workflowId, newExecId, prepared, safeStop);

    // restartNodeId = "terminal" (index 2) → trigger (0) and processor (1) get bypassed
    // No checkpoints provided → both become Flux.empty assemblers
    StepVerifier.create(
            orchestrator.restartFromNode(
                sessionId, workflowId, prevExecId, newExecId, prepared, "terminal", Map.of()))
        .verifyComplete();

    verify(compiler).compileAssemblers(any(), any(), any(), any());
    verify(compiler)
        .executeTemplate(
            eq(newExecId), any(), eq(3), any(), eq(sessionId), eq(workflowId), anyList());
  }

  // --- helpers ---

  private WorkflowDefinition buildMinimalDefinition(final String workflowId) {
    final var node = new WorkflowDefinition.Node("node-a", "trigger-type", Map.of());
    final var edge = new WorkflowDefinition.Edge("node-a", "node-a", "default");
    return new WorkflowDefinition(workflowId, "Test workflow", List.of(node), List.of(edge));
  }

  private PreparedWorkflow buildPreparedWorkflow(final List<String> nodeIds) {
    final List<WorkflowNode> nodes =
        nodeIds.stream().map(id -> new WorkflowNode(id, "type", Map.of())).toList();
    return buildPreparedWorkflowFromNodes(nodes);
  }

  private PreparedWorkflow buildPreparedWorkflowFromNodes(final List<WorkflowNode> nodes) {
    final WorkflowTemplate template = org.mockito.Mockito.mock(WorkflowTemplate.class);
    final Map<String, List<WorkflowNode>> emptyPerNode =
        nodes.stream()
            .collect(java.util.stream.Collectors.toMap(WorkflowNode::nodeId, n -> List.of()));
    return new PreparedWorkflow(List.of(), emptyPerNode, emptyPerNode, Map.of(), nodes, template);
  }

  private void setupExecutionControl(
      final String sessionId,
      final String workflowId,
      final String executionId,
      final PreparedWorkflow prepared,
      final Map<String, Object> payload,
      final Sinks.One<Void> immediateStop,
      final Sinks.One<Void> safeStop) {
    when(executionControlFactory.create(sessionId, workflowId, executionId, prepared, payload))
        .thenReturn(executionControl);
    doNothing().when(executionControlRegistry).register(executionControl);
    doNothing().when(executionControlRegistry).unregister(executionId);
    doNothing().when(checkpointStore).clear(executionId);
    when(executionControl.immediateStopSink()).thenReturn(immediateStop);
    when(executionControl.safeStopSink()).thenReturn(safeStop);
  }

  private void setupRestartControl(
      final String sessionId,
      final String workflowId,
      final String executionId,
      final PreparedWorkflow prepared,
      final Sinks.One<Void> safeStop) {
    when(executionControlFactory.create(
            eq(sessionId), eq(workflowId), eq(executionId), eq(prepared), eq(Map.of())))
        .thenReturn(executionControl);
    doNothing().when(executionControlRegistry).register(executionControl);
    doNothing().when(executionControlRegistry).unregister(executionId);
    doNothing().when(checkpointStore).clear(executionId);
    when(executionControl.safeStopSink()).thenReturn(safeStop);
  }
}
