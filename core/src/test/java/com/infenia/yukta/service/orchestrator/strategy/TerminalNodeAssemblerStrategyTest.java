// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.message.channel.DirectNodeMessageChannel;
import com.infenia.yukta.message.channel.NodeMessageChannel;
import com.infenia.yukta.message.channel.NodeMessageChannelProvider;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.gateway.ResultCollector;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseShortArrayInitializer",
  "PMD.ShortVariable",
  "PMD.UseVarargs"
})
class TerminalNodeAssemblerStrategyTest {

  @Mock private TaskTrackerService tracker;
  @Mock private StreamTopologyDecorator streamTopologyDecorator;
  @Mock private NodeMessageChannelProvider channelProvider;
  @Mock private ActivePluginRegistry activePluginRegistry;

  private TerminalNodeAssemblerStrategy strategy;

  private static final String NODE_ID = "terminal-node-1";
  private static final String EXECUTION_ID = "exec-001";
  private static final String SESSION_ID = "session-001";
  private static final String WORKFLOW_ID = "wf-001";

  @BeforeEach
  void setUp() {
    when(channelProvider.channelFor(any(), any())).thenReturn(new DirectNodeMessageChannel());
    strategy =
        new TerminalNodeAssemblerStrategy(
            tracker,
            Schedulers.boundedElastic(),
            streamTopologyDecorator,
            channelProvider,
            activePluginRegistry);
  }

  // ── supports() ────────────────────────────────────────────────────────────

  @Test
  void supports_terminalPlugin_returnsTrue() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    assertThat(strategy.supports(terminal, false)).isTrue();
  }

  @Test
  void supports_terminalPluginWithParents_returnsTrue() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    assertThat(strategy.supports(terminal, true)).isTrue();
  }

  @Test
  void supports_triggerPlugin_returnsFalse() {
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    assertThat(strategy.supports(trigger, false)).isFalse();
  }

  @Test
  void supports_processorPlugin_returnsFalse() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    assertThat(strategy.supports(processor, false)).isFalse();
  }

  @Test
  void supports_genericWorkflowPlugin_returnsFalse() {
    final Plugin plugin = mock(Plugin.class);
    assertThat(strategy.supports(plugin, false)).isFalse();
  }

  // ── createAssembler() ─────────────────────────────────────────────────────

  @Test
  void createAssembler_returnsNonNullAssembler() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);

    assertThat(assembler).isNotNull();
  }

  @Test
  void createAssembler_assembleAddsTerminalMonoToContext() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(parentStream);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.terminals()).hasSize(1);
  }

  @Test
  void createAssembler_terminalCompletion_emitsRunningAndSuccessTrackerEvents() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final AssemblyContext context = buildContext(Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.terminals().getFirst()).verifyComplete();

    verify(tracker, atLeastOnce())
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), anyString(), any(TaskStatus.class), any());
    verify(activePluginRegistry).register(WORKFLOW_ID, NODE_ID, terminal);
    verify(activePluginRegistry).unregister(WORKFLOW_ID, NODE_ID);
  }

  @Test
  void createAssembler_terminalError_emitsFailureTrackerEvent() {
    final RuntimeException error = new RuntimeException("terminal failure");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.error(error));
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final AssemblyContext context = buildContext(Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.terminals().getFirst()).verifyError(RuntimeException.class);

    verify(tracker, atLeastOnce())
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), anyString(), eq(TaskStatus.FAILURE), any());
    verify(activePluginRegistry).register(WORKFLOW_ID, NODE_ID, terminal);
    verify(activePluginRegistry).unregister(WORKFLOW_ID, NODE_ID);
  }

  @Test
  void createAssembler_stalledAfterFirstInput_timesOut() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final Message<?> first = DefaultMessage.create(UUID.randomUUID(), "data");
    final Flux<Message<?>> stalled = Flux.concat(Flux.just(first), Flux.never());
    final AssemblyContext context = buildContext(stalled);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(stalled);

    strategy
        .createAssembler(node, terminal, Duration.ofMillis(100), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.terminals().getFirst())
        .verifyError(java.util.concurrent.TimeoutException.class);
  }

  @Test
  void createAssembler_cancelledTerminal_reportsCancelled() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });
    when(terminal.isBlocking()).thenReturn(false);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final Flux<Message<?>> waiting = Flux.never();
    final AssemblyContext context = buildContext(waiting);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(waiting);
    strategy
        .createAssembler(node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.terminals().getFirst()).expectSubscription().thenCancel().verify();

    verify(tracker)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), anyString(), eq(TaskStatus.CANCELLED), any());
  }

  @Test
  void createAssembler_stalledAfterFirstInput_reportsTimedOut() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });
    when(terminal.isBlocking()).thenReturn(false);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final Message<?> first = DefaultMessage.create(UUID.randomUUID(), "data");
    final Flux<Message<?>> stalled = Flux.concat(Flux.just(first), Flux.never());
    final AssemblyContext context = buildContext(stalled);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(stalled);
    strategy
        .createAssembler(node, terminal, Duration.ofMillis(100), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.terminals().getFirst())
        .verifyError(java.util.concurrent.TimeoutException.class);

    verify(tracker)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), anyString(), eq(TaskStatus.TIMED_OUT), any());
  }

  @Test
  void createAssembler_noInputYet_doesNotTimeOut() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final Flux<Message<?>> waiting = Flux.never();
    final AssemblyContext context = buildContext(waiting);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(waiting);

    strategy
        .createAssembler(node, terminal, Duration.ofMillis(100), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    // The upstream has produced nothing yet, so this node's idle clock has not started.
    StepVerifier.create(context.terminals().getFirst())
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(500))
        .thenCancel()
        .verify();
  }

  @Test
  void createAssembler_blockingTerminal_subscribesOnScheduler() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());
    when(terminal.isBlocking()).thenReturn(true);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final AssemblyContext context = buildContext(Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.terminals()).hasSize(1);
    StepVerifier.create(context.terminals().getFirst()).verifyComplete();
  }

  @Test
  void createAssembler_resultCollectorInContext_addsMessagesToCollector() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "result-data");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    final ResultCollector collector = mock(ResultCollector.class);

    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final AssemblyContext context = buildContext(Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    final Mono<Void> terminalMono =
        context.terminals().getFirst().contextWrite(Context.of("resultCollector", collector));

    StepVerifier.create(terminalMono).verifyComplete();

    verify(collector).add(any());
  }

  @Test
  void createAssembler_noResultCollectorInContext_completesNormally() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final AssemblyContext context = buildContext(Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.terminals().getFirst()).verifyComplete();
  }

  @Test
  void createAssembler_multipleParentEdges_mergesStreams() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "a");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "b");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final ParentEdgeInfo[] parentEdges = {
      new ParentEdgeInfo(0, "source-1", null), new ParentEdgeInfo(1, "source-2", null)
    };
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1), Flux.just(msg2), null};
    final AssemblyContext context = buildContextWithStreams(streams);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg1, msg2));

    final NodeAssembler assembler =
        strategy.createAssembler(node, terminal, Duration.ofSeconds(5), 0, 1024, parentEdges);
    assembler.assemble(context);

    verify(streamTopologyDecorator).mergeParentStreams(any(), any(ParentEdgeInfo[].class));
    assertThat(context.terminals()).hasSize(1);
  }

  @Test
  void createAssembler_multipleAssembles_eachAddsOwnTerminalMono() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node1 = new WorkflowNode("t1", "terminal", Map.of());
    final WorkflowNode node2 = new WorkflowNode("t2", "terminal", Map.of());

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[2];
    final AssemblyContext context = buildContextWithStreams(streams);

    final NodeAssembler a1 =
        strategy.createAssembler(
            node1, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    final NodeAssembler a2 =
        strategy.createAssembler(
            node2, terminal, Duration.ofSeconds(5), 1, 1024, new ParentEdgeInfo[0]);

    a1.assemble(context);
    a2.assemble(context);

    assertThat(context.terminals()).hasSize(2);
  }

  @Test
  void createAssembler_callsInboundChannel() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final NodeMessageChannel channel = mock(NodeMessageChannel.class);
    when(channelProvider.channelFor(eq(NODE_ID), any())).thenReturn(channel);
    when(channel.inbound(eq(NODE_ID), eq(EXECUTION_ID), any())).thenAnswer(i -> i.getArgument(2));

    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final AssemblyContext context = buildContext(Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(channelProvider).channelFor(eq(NODE_ID), any());
    verify(channel).inbound(eq(NODE_ID), eq(EXECUTION_ID), any());
    verify(channel, never()).outbound(any(), any(), any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContext(final Flux<Message<?>> parentStream) {
    final Flux<Message<?>>[] streams = new Flux[] {parentStream};
    return buildContextWithStreams(streams);
  }

  private AssemblyContext buildContextWithStreams(final Flux<Message<?>>[] streams) {
    final ExecutionControl control =
        new ExecutionControl(
            SESSION_ID,
            WORKFLOW_ID,
            EXECUTION_ID,
            null,
            Map.of(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            new AtomicBoolean());

    return new AssemblyContext(
        EXECUTION_ID,
        SESSION_ID,
        WORKFLOW_ID,
        Map.of(),
        control,
        streams,
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>());
  }
}
