// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.factory.ExecutionControlFactory;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.compiler.WorkflowCompiler;
import com.infenia.yukta.service.orchestrator.preparator.WorkflowPreparator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.LawOfDemeter",
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

  private final ExecutionArchive executionArchive = new ExecutionArchive();
  private final ExecutionLogStore executionLog = org.mockito.Mockito.mock(ExecutionLogStore.class);

  @BeforeEach
  void setUp() {
    lenient().when(checkpointStore.save(anyString(), anyString(), any())).thenReturn(Mono.empty());
    lenient()
        .when(tracker.finishWorkflow(anyString(), any(TaskStatus.class)))
        .thenReturn(Mono.empty());
    orchestrator =
        new WorkflowOrchestrator(
            tracker,
            executionControlRegistry,
            executionControlFactory,
            checkpointStore,
            executionArchive,
            executionLog,
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
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(sessionId, workflowId, executionId, prepared, payload, safeStop);
    when(tracker.startWorkflow(eq(executionId), eq(sessionId), eq(workflowId), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(eq(executionId), eq(payload))).thenReturn(Mono.empty());

    StepVerifier.create(orchestrator.execute(sessionId, workflowId, executionId, prepared, payload))
        .verifyComplete();

    verify(executionControlFactory).create(sessionId, workflowId, executionId, prepared, payload);
    verify(executionControlRegistry).register(executionControl);
    verify(tracker).startWorkflow(executionId, sessionId, workflowId, List.of("node-a"));
    verify(executionControlRegistry).unregister(executionId);
    verify(checkpointStore, never()).clear(executionId);
  }

  @Test
  void execute_unregistersAndClearsCheckpointOnError() {
    final String sessionId = "sess-err";
    final String workflowId = "wf-err";
    final String executionId = "exec-err";
    final Map<String, Object> payload = Map.of("k", "v");

    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-x"));
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(sessionId, workflowId, executionId, prepared, payload, safeStop);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(anyString(), any()))
        .thenReturn(Mono.error(new RuntimeException("execution failed")));

    StepVerifier.create(orchestrator.execute(sessionId, workflowId, executionId, prepared, payload))
        .expectError(RuntimeException.class)
        .verify();

    verify(executionControlRegistry).unregister(executionId);
    verify(checkpointStore, never()).clear(executionId);
  }

  @Test
  void execute_runThatEndsAfterStopRequest_isReportedStoppedNotSuccess() {
    final String sessionId = "sess-drain";
    final String workflowId = "wf-drain";
    final String executionId = "exec-drain";
    final Map<String, Object> payload = Map.of();
    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-d"));

    setupExecutionControl(sessionId, workflowId, executionId, prepared, payload, Sinks.one());
    when(executionControl.stopRequested()).thenReturn(new AtomicBoolean(true));
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    // the drain lets the template finish on its own
    when(prepared.template().instantiate(anyString(), any())).thenReturn(Mono.empty());

    StepVerifier.create(orchestrator.execute(sessionId, workflowId, executionId, prepared, payload))
        .verifyComplete();

    verify(tracker).finishWorkflow(executionId, TaskStatus.STOPPED);
    verify(tracker, never()).finishWorkflow(executionId, TaskStatus.SUCCESS);
  }

  @Test
  void execute_safeStopSinkTerminatesExecution() {
    final String sessionId = "sess-safe";
    final String workflowId = "wf-safe";
    final String executionId = "exec-safe";
    final Map<String, Object> payload = Map.of();

    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("node-q"));
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(sessionId, workflowId, executionId, prepared, payload, safeStop);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(anyString(), any())).thenReturn(Mono.never());

    final Mono<Void> execution =
        orchestrator.execute(sessionId, workflowId, executionId, prepared, payload);

    StepVerifier.create(execution).then(() -> safeStop.tryEmitEmpty()).verifyComplete();

    verify(tracker).finishWorkflow(executionId, TaskStatus.STOPPED);
    verify(tracker, never()).finishWorkflow(executionId, TaskStatus.SUCCESS);

    verify(executionControlRegistry).unregister(executionId);
    verify(checkpointStore, never()).clear(executionId);
  }

  @Test
  void execute_tracksAllNodeIds() {
    final String sessionId = "sess-nodes";
    final String workflowId = "wf-nodes";
    final String executionId = "exec-nodes";
    final Map<String, Object> payload = Map.of("p", 1);

    final PreparedWorkflow prepared =
        buildPreparedWorkflow(List.of("trigger", "processor", "terminal"));
    final Sinks.One<Void> safeStop = Sinks.one();

    setupExecutionControl(sessionId, workflowId, executionId, prepared, payload, safeStop);
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
    verify(checkpointStore, never()).clear(newExecId);
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
                Map.of("trigger", List.of(checkpoint))))
        .verifyComplete();

    verify(compiler).compileAssemblers(any(), any(), any(), any());

    // the pre-restart assembler now replays the parent's checkpoint
    final AssemblyContext context = org.mockito.Mockito.mock(AssemblyContext.class);
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[2];
    when(context.streams()).thenReturn(streams);
    assemblers[0].assemble(context);
    StepVerifier.create(streams[0]).expectNext(checkpoint).verifyComplete();
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
    verify(checkpointStore, never()).clear(newExecId);
  }

  @Test
  void restartFromNode_unknownNode_errorsWithoutRegisteringExecution() {
    final WorkflowNode node = new WorkflowNode("trigger", "t", Map.of());
    final PreparedWorkflow prepared = buildPreparedWorkflowFromNodes(List.of(node));

    final Mono<Void> restart =
        orchestrator.restartFromNode("s", "w", "prev", "new", prepared, "ghost", Map.of());

    StepVerifier.create(restart)
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Unknown restart node: ghost"))
        .verify();
    verify(executionControlRegistry, never()).register(any());
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

    verify(tracker).finishWorkflow(newExecId, TaskStatus.STOPPED);
    verify(tracker, never()).finishWorkflow(newExecId, TaskStatus.SUCCESS);

    verify(executionControlRegistry).unregister(newExecId);
    verify(checkpointStore, never()).clear(newExecId);
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

    // without a checkpoint the bypassed nodes emit nothing
    final AssemblyContext context = org.mockito.Mockito.mock(AssemblyContext.class);
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[3];
    when(context.streams()).thenReturn(streams);
    assemblers[0].assemble(context);
    StepVerifier.create(streams[0]).verifyComplete();
  }

  @Test
  void restartFromNode_rerunsOnlyDescendants_replaysBoundary_andLeavesIndependentBranchesSilent() {
    final WorkflowNode source = new WorkflowNode("t1", "trigger-type", Map.of());
    final WorkflowNode worker = new WorkflowNode("p", "proc-type", Map.of());
    final WorkflowNode independent = new WorkflowNode("t2", "trigger-type", Map.of());
    final WorkflowTemplate template = org.mockito.Mockito.mock(WorkflowTemplate.class);
    final PreparedWorkflow prepared =
        new PreparedWorkflow(
            List.of(),
            Map.of("t1", List.of(worker), "p", List.of(), "t2", List.of()),
            Map.of("t1", List.of(), "p", List.of(source), "t2", List.of()),
            Map.of(),
            List.of(source, worker, independent),
            template);

    final NodeAssembler kept = ctx -> {};
    final NodeAssembler[] assemblers = new NodeAssembler[] {ctx -> {}, kept, ctx -> {}};
    when(compiler.compileAssemblers(any(), any(), any(), any())).thenReturn(assemblers);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(compiler.executeTemplate(
            anyString(), any(), eq(3), any(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    setupRestartControl("s", "w", "new", prepared, Sinks.one());
    final Message<?> recorded = org.mockito.Mockito.mock(Message.class);

    StepVerifier.create(
            orchestrator.restartFromNode(
                "s", "w", "old", "new", prepared, "p", Map.of("t1", List.of(recorded))))
        .verifyComplete();

    assertThat(assemblers[1]).isSameAs(kept);
    final AssemblyContext context = org.mockito.Mockito.mock(AssemblyContext.class);
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[3];
    when(context.streams()).thenReturn(streams);
    assemblers[0].assemble(context);
    assemblers[2].assemble(context);
    StepVerifier.create(streams[0]).expectNext(recorded).verifyComplete();
    StepVerifier.create(streams[2]).verifyComplete();
    // the replayed input is recorded under the new execution so it can be restarted in turn
    verify(checkpointStore).save("new", "t1", recorded);
    assertThat(executionArchive.find("new")).isPresent();
  }

  @Test
  void execute_archivesTheRun_andKeepsItsRecordedOutputsAfterItEnds() {
    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("n1"));
    setupExecutionControl("s", "w", "e1", prepared, Map.of("k", "v"), Sinks.one());
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(anyString(), any())).thenReturn(Mono.empty());

    StepVerifier.create(orchestrator.execute("s", "w", "e1", prepared, Map.of("k", "v")))
        .verifyComplete();

    final var archived = executionArchive.find("e1").orElseThrow();
    assertThat(archived.prepared()).isSameAs(prepared);
    assertThat(archived.payload()).containsEntry("k", "v");
    assertThat(archived.definitionVersion()).hasSize(16);
    final org.mockito.ArgumentCaptor<ExecutionLogEvent> logged =
        org.mockito.ArgumentCaptor.forClass(ExecutionLogEvent.class);
    verify(executionLog).append(logged.capture());
    assertThat(logged.getValue().type()).isEqualTo(ExecutionLogEvent.Type.RUN_STARTED);
    assertThat(logged.getValue().executionId()).isEqualTo("e1");
    assertThat(logged.getValue().definitionVersion()).isEqualTo(archived.definitionVersion());
    verify(checkpointStore, never()).clear("e1");
  }

  @Test
  void execute_freesTheOutputsOfExecutionsThatFallOutOfTheArchive() {
    final ExecutionArchive smallArchive = new ExecutionArchive(1);
    final WorkflowOrchestrator small =
        new WorkflowOrchestrator(
            tracker,
            executionControlRegistry,
            executionControlFactory,
            checkpointStore,
            smallArchive,
            executionLog,
            compiler,
            preparator);
    final PreparedWorkflow prepared = buildPreparedWorkflow(List.of("n1"));
    lenient()
        .when(executionControlFactory.create(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(executionControl);
    lenient().when(executionControl.safeStopSink()).thenAnswer(inv -> Sinks.one());
    lenient().when(executionControl.stopRequested()).thenAnswer(inv -> new AtomicBoolean());
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(Mono.empty());
    when(prepared.template().instantiate(anyString(), any())).thenReturn(Mono.empty());

    StepVerifier.create(small.execute("s", "w", "e1", prepared, Map.of())).verifyComplete();
    StepVerifier.create(small.execute("s", "w", "e2", prepared, Map.of())).verifyComplete();

    verify(checkpointStore).clear("e1");
    verify(checkpointStore, never()).clear("e2");
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
    // The nodes form a chain: each one feeds the next.
    final Map<String, List<WorkflowNode>> children = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<String, List<WorkflowNode>> parents = new java.util.concurrent.ConcurrentHashMap<>();
    nodes.forEach(
        n -> {
          children.put(n.nodeId(), List.of());
          parents.put(n.nodeId(), List.of());
        });
    for (int i = 0; i + 1 < nodes.size(); i++) {
      children.put(nodes.get(i).nodeId(), List.of(nodes.get(i + 1)));
      parents.put(nodes.get(i + 1).nodeId(), List.of(nodes.get(i)));
    }
    return new PreparedWorkflow(List.of(), children, parents, Map.of(), nodes, template);
  }

  private void setupExecutionControl(
      final String sessionId,
      final String workflowId,
      final String executionId,
      final PreparedWorkflow prepared,
      final Map<String, Object> payload,
      final Sinks.One<Void> safeStop) {
    when(executionControlFactory.create(sessionId, workflowId, executionId, prepared, payload))
        .thenReturn(executionControl);
    doNothing().when(executionControlRegistry).register(executionControl);
    doNothing().when(executionControlRegistry).unregister(executionId);
    when(executionControl.safeStopSink()).thenReturn(safeStop);
    lenient().when(executionControl.stopRequested()).thenReturn(new AtomicBoolean());
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
    when(executionControl.safeStopSink()).thenReturn(safeStop);
    lenient().when(executionControl.stopRequested()).thenReturn(new AtomicBoolean());
  }
}
