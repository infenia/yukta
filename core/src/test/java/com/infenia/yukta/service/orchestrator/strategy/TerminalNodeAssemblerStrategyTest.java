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
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.gateway.ResultCollector;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
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
class TerminalNodeAssemblerStrategyTest {

  @Mock private TaskTrackerService tracker;
  @Mock private StreamTopologyDecorator streamTopologyDecorator;
  @Mock private NodeMessageChannelProvider channelProvider;

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
            tracker, Schedulers.boundedElastic(), streamTopologyDecorator, channelProvider);
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
    final AssemblyContext context = buildContext(NODE_ID, parentStream);

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
    final AssemblyContext context = buildContext(NODE_ID, Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.terminals().get(0)).verifyComplete();

    verify(tracker, atLeastOnce())
        .emitTaskStatusEvent(eq(EXECUTION_ID), eq(NODE_ID), anyString(), anyString(), any());
  }

  @Test
  void createAssembler_terminalError_emitsFailureTrackerEvent() {
    final RuntimeException error = new RuntimeException("terminal failure");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.error(error));
    when(terminal.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final AssemblyContext context = buildContext(NODE_ID, Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.terminals().get(0)).verifyError(RuntimeException.class);

    verify(tracker, atLeastOnce())
        .emitTaskStatusEvent(eq(EXECUTION_ID), eq(NODE_ID), anyString(), eq("FAILURE"), any());
  }

  @Test
  void createAssembler_blockingTerminal_subscribesOnScheduler() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());
    when(terminal.isBlocking()).thenReturn(true);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "terminal", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.terminals()).hasSize(1);
    StepVerifier.create(context.terminals().get(0)).verifyComplete();
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
    final AssemblyContext context = buildContext(NODE_ID, Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    final Mono<Void> terminalMono =
        context.terminals().get(0).contextWrite(Context.of("resultCollector", collector));

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
    final AssemblyContext context = buildContext(NODE_ID, Flux.just(msg));

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, terminal, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.terminals().get(0)).verifyComplete();
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
    final AssemblyContext context = buildContextWithStreams(NODE_ID, streams);

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
    final AssemblyContext context = buildContextWithStreams(NODE_ID, streams);

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
    final AssemblyContext context = buildContext(NODE_ID, Flux.just(msg));

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
  private AssemblyContext buildContext(final String nodeId, final Flux<Message<?>> parentStream) {
    final Flux<Message<?>>[] streams = new Flux[] {parentStream};
    return buildContextWithStreams(nodeId, streams);
  }

  private AssemblyContext buildContextWithStreams(
      final String nodeId, @SuppressWarnings("unchecked") final Flux<Message<?>>[] streams) {
    final ExecutionControl control =
        new ExecutionControl(
            SESSION_ID,
            WORKFLOW_ID,
            EXECUTION_ID,
            null,
            Map.of(),
            Sinks.one(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

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
