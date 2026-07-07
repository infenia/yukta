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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseShortArrayInitializer"
})
class ProcessorNodeAssemblerStrategyTest {

  @Mock private TaskTrackerService tracker;
  @Mock private StreamTopologyDecorator streamTopologyDecorator;
  @Mock private NodeMessageChannelProvider channelProvider;
  @Mock private ActivePluginRegistry activePluginRegistry;

  private ProcessorNodeAssemblerStrategy strategy;

  private static final String NODE_ID = "processor-node-1";
  private static final String EXECUTION_ID = "exec-001";
  private static final String SESSION_ID = "session-001";
  private static final String WORKFLOW_ID = "wf-001";

  @BeforeEach
  void setUp() {
    when(channelProvider.channelFor(any(), any())).thenReturn(new DirectNodeMessageChannel());
    strategy =
        new ProcessorNodeAssemblerStrategy(
            tracker,
            Schedulers.boundedElastic(),
            streamTopologyDecorator,
            channelProvider,
            activePluginRegistry);
  }

  // ── supports() ────────────────────────────────────────────────────────────

  @Test
  void supports_processorPlugin_returnsTrue() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    assertThat(strategy.supports(processor, false)).isTrue();
  }

  @Test
  void supports_processorPluginWithParents_returnsTrue() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    assertThat(strategy.supports(processor, true)).isTrue();
  }

  @Test
  void supports_triggerPlugin_returnsFalse() {
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    assertThat(strategy.supports(trigger, false)).isFalse();
  }

  @Test
  void supports_terminalPlugin_returnsFalse() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    assertThat(strategy.supports(terminal, false)).isFalse();
  }

  @Test
  void supports_genericWorkflowPlugin_returnsFalse() {
    final Plugin plugin = mock(Plugin.class);
    assertThat(strategy.supports(plugin, false)).isFalse();
  }

  // ── createAssembler() ─────────────────────────────────────────────────────

  @Test
  void createAssembler_returnsNonNullAssembler() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);

    assertThat(assembler).isNotNull();
  }

  @Test
  void createAssembler_normalPath_invokesProcessAndBroadcasts() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "output");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(out));
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, null);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    final Flux<Message<?>> broadcast = Flux.just(out);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(broadcast);

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(processor).process(any(), any());
    assertThat(context.streams()[0]).isSameAs(broadcast);
  }

  @Test
  void createAssembler_streamSubscribedAndCompleted_registersThenUnregistersPlugin() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "output");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(out));
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, null);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    reactor.test.StepVerifier.create(context.streams()[0]).expectNextCount(1).verifyComplete();

    verify(activePluginRegistry).register(WORKFLOW_ID, NODE_ID, processor);
    verify(activePluginRegistry).unregister(WORKFLOW_ID, NODE_ID);
  }

  @Test
  void createAssembler_skipFlagTrue_bypassesProcess() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);

    final AtomicBoolean skipFlag = new AtomicBoolean(true);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, skipFlag);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    // Skip flag set — processor.process() should NOT be called
    verify(processor, never()).process(any(), any());
  }

  @Test
  void createAssembler_skipFlagFalse_doesNotBypassProcess() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "output");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(out));
    when(processor.isBlocking()).thenReturn(false);

    final AtomicBoolean skipFlag = new AtomicBoolean(false);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, skipFlag);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(out));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(processor).process(any(), any());
  }

  @Test
  void createAssembler_noSkipFlag_doesNotBypassProcess() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "output");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(out));
    when(processor.isBlocking()).thenReturn(false);

    // null skip flag (not present in map)
    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContextNoSkipFlag(NODE_ID, parentStream);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(out));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(processor).process(any(), any());
  }

  @Test
  void createAssembler_blockingProcessor_subscribesOnScheduler() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "output");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(out));
    when(processor.isBlocking()).thenReturn(true);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContextNoSkipFlag(NODE_ID, parentStream);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(out));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.streams()[0]).isNotNull();
  }

  @Test
  void createAssembler_multipleParentEdges_mergesStreams() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "a");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "b");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(msg1, msg2));
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final ParentEdgeInfo[] parentEdges = {
      new ParentEdgeInfo(0, "source-1", null), new ParentEdgeInfo(1, "source-2", null)
    };
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[] {Flux.just(msg1), Flux.just(msg2), null};
    final AssemblyContext context = buildContextWithStreams(NODE_ID, streams, null);

    final Flux<Message<?>> merged = Flux.just(msg1, msg2);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(merged);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(msg1, msg2));

    final NodeAssembler assembler =
        strategy.createAssembler(node, processor, Duration.ofSeconds(5), 2, 1024, parentEdges);
    assembler.assemble(context);

    verify(streamTopologyDecorator).mergeParentStreams(any(), any(ParentEdgeInfo[].class));
    assertThat(context.streams()[2]).isNotNull();
  }

  @Test
  void createAssembler_indexPositionsStreamCorrectly() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "out");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(out));
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final int index = 3;
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[5];
    final AssemblyContext context = buildContextWithStreams(NODE_ID, streams, null);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));
    final Flux<Message<?>> broadcast = Flux.just(out);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(broadcast);

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), index, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.streams()[index]).isSameAs(broadcast);
  }

  @Test
  void createAssembler_normalPath_callsInboundAndOutboundChannel() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "output");
    final NodeMessageChannel channel = mock(NodeMessageChannel.class);
    when(channelProvider.channelFor(eq(NODE_ID), any())).thenReturn(channel);
    when(channel.inbound(eq(NODE_ID), eq(EXECUTION_ID), any())).thenAnswer(i -> i.getArgument(2));
    when(channel.outbound(eq(NODE_ID), eq(EXECUTION_ID), any())).thenAnswer(i -> i.getArgument(2));

    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenReturn(Flux.just(out));
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContextNoSkipFlag(NODE_ID, parentStream);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(out));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(channelProvider).channelFor(eq(NODE_ID), any());
    verify(channel).inbound(eq(NODE_ID), eq(EXECUTION_ID), any());
    verify(channel).outbound(eq(NODE_ID), eq(EXECUTION_ID), any());
  }

  @Test
  void createAssembler_skipFlagTrue_doesNotCallChannelInboundOrOutbound() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final NodeMessageChannel channel = mock(NodeMessageChannel.class);
    when(channelProvider.channelFor(any(), any())).thenReturn(channel);

    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    final AtomicBoolean skipFlag = new AtomicBoolean(true);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, skipFlag);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(channel, never()).inbound(any(), any(), any());
    verify(channel, never()).outbound(any(), any(), any());
    verify(channelProvider, never()).channelFor(any(), any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContext(
      final String nodeId, final Flux<Message<?>> parentStream, final AtomicBoolean skipFlag) {
    final Flux<Message<?>>[] streams = new Flux[] {parentStream};
    return buildContextWithStreams(nodeId, streams, skipFlag);
  }

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContextNoSkipFlag(
      final String nodeId, final Flux<Message<?>> parentStream) {
    final Flux<Message<?>>[] streams = new Flux[] {parentStream};
    return buildContextWithStreams(nodeId, streams, null);
  }

  private AssemblyContext buildContextWithStreams(
      final String nodeId,
      @SuppressWarnings("unchecked") final Flux<Message<?>>[] streams,
      final AtomicBoolean skipFlag) {
    final Map<String, AtomicBoolean> skipFlags =
        skipFlag != null ? Map.of(nodeId, skipFlag) : Map.of();

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
            skipFlags,
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
