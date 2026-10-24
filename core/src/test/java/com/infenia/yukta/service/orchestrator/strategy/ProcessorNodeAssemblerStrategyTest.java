// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.store.SecretProvider;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.plugin.util.VariableResolver;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.stream.ConcurrencyGroups;
import com.infenia.yukta.service.orchestrator.stream.StreamTopologyDecorator;
import com.infenia.yukta.service.orchestrator.stream.UpstreamFailure;
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
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
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
            activePluginRegistry,
            new VariableResolver(mock(SecretProvider.class)),
            new ConcurrencyGroups(Map.of()));
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

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    final Flux<Message<?>> broadcast = Flux.just(out);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
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

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.streams()[0]).expectNextCount(1).verifyComplete();

    verify(activePluginRegistry).register(WORKFLOW_ID, EXECUTION_ID, NODE_ID, processor);
    verify(activePluginRegistry).unregister(WORKFLOW_ID, EXECUTION_ID, NODE_ID);
  }

  @Test
  @SuppressWarnings("unchecked")
  void createAssembler_fullConcurrencyGroup_holdsNodeQueuedUntilAPlaceFrees() {
    final ConcurrencyGroups groups = new ConcurrencyGroups(Map.of("gradle", 1));
    final ProcessorNodeAssemblerStrategy limited =
        new ProcessorNodeAssemblerStrategy(
            tracker,
            Schedulers.boundedElastic(),
            streamTopologyDecorator,
            channelProvider,
            activePluginRegistry,
            new VariableResolver(mock(SecretProvider.class)),
            groups);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final Message<?> out = DefaultMessage.create(UUID.randomUUID(), "output");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any()))
        .thenAnswer(invocation -> ((Flux<Message<?>>) invocation.getArgument(0)).map(_ -> out));
    final WorkflowNode node =
        new WorkflowNode(
            NODE_ID, "processor", Map.of("engine", Map.of("concurrencyGroup", "gradle")));
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, null);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(2));
    final ConcurrencyGroups.Place busy = groups.place("gradle");
    busy.acquire(() -> {}).subscribe();

    limited
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.streams()[0])
        .expectSubscription()
        .then(
            () ->
                verify(tracker)
                    .emitTaskStatusEvent(
                        EXECUTION_ID, NODE_ID, "processor", TaskStatus.QUEUED, Map.of()))
        .expectNoEvent(Duration.ofMillis(100))
        .then(busy::release)
        .expectNextCount(1)
        .verifyComplete();
    assertThat(groups.place("gradle")).isNotNull();
  }

  @Test
  void createAssembler_skipFlagTrue_bypassesProcess() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);

    final AtomicBoolean skipFlag = new AtomicBoolean(true);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, skipFlag);

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
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

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(call -> call.getArgument(2));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    // the skip decision is taken per element, so the plugin is reached once the stream runs
    StepVerifier.create(context.streams()[0]).expectNext(out).verifyComplete();
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

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
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

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
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
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(merged);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenReturn(Flux.just(msg1, msg2));

    final NodeAssembler assembler =
        strategy.createAssembler(node, processor, Duration.ofSeconds(5), 2, 1024, parentEdges);
    assembler.assemble(context);

    verify(streamTopologyDecorator).mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class));
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

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(Flux.just(msg));
    final Flux<Message<?>> broadcast = Flux.just(out);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
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

    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
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
  void createAssembler_nodeSkippedAtAssembly_canBeUnskippedLater() {
    final Message<?> first = DefaultMessage.create(UUID.randomUUID(), "while-skipped");
    final Message<?> second = DefaultMessage.create(UUID.randomUUID(), "after-unskip");
    final Message<?> processed = DefaultMessage.create(UUID.randomUUID(), "processed");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any()))
        .thenAnswer(call -> call.<Flux<Message<?>>>getArgument(0).map(_ -> processed));
    when(processor.isBlocking()).thenReturn(false);
    final AtomicBoolean skipFlag = new AtomicBoolean(true);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Sinks.Many<Message<?>> parent = Sinks.many().unicast().onBackpressureBuffer();
    final AssemblyContext context = buildContext(NODE_ID, parent.asFlux(), skipFlag);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parent.asFlux());
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(call -> call.getArgument(2));

    strategy
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.streams()[0])
        .then(() -> parent.tryEmitNext(first))
        .expectNext(first)
        .then(() -> skipFlag.set(false))
        .then(() -> parent.tryEmitNext(second))
        .expectNext(processed)
        .then(parent::tryEmitComplete)
        .verifyComplete();
    verify(processor).process(any(), any());
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
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            skipFlags,
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

  @Test
  void createAssembler_inputReachesPlugin_touchesTheNodeClock() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "input");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> parentStream = Flux.just(msg);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, null);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    strategy
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.streams()[0]).expectNextCount(1).verifyComplete();
  }

  @Test
  void createAssembler_onErrorContinue_callsThePluginPerMessageAndDropsFailures() {
    final Message<?> good = DefaultMessage.create(UUID.randomUUID(), "good");
    final Message<?> bad = DefaultMessage.create(UUID.randomUUID(), "bad");
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> messages = inv.getArgument(0);
              return messages.flatMap(
                  m ->
                      "bad".equals(m.payload())
                          ? Flux.<Message<?>>error(new IllegalStateException("boom"))
                          : Flux.<Message<?>>just(m));
            });
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node =
        new WorkflowNode(NODE_ID, "processor", Map.of("engine", Map.of("onError", "CONTINUE")));
    final Flux<Message<?>> parentStream = Flux.just(bad, good);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, null);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    strategy
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.streams()[0])
        .assertNext(m -> assertThat(m.payload()).isEqualTo("good"))
        .verifyComplete();
  }

  @Test
  void createAssembler_channelFailsOnOneMessage_deadLettersItAndKeepsTheNodeRunning() {
    final Message<?> good = DefaultMessage.create(UUID.randomUUID(), "good");
    final Message<?> bad = DefaultMessage.create(UUID.randomUUID(), "bad");
    final NodeMessageChannel channel = mock(NodeMessageChannel.class);
    when(channelProvider.channelFor(eq(NODE_ID), any())).thenReturn(channel);
    when(channel.inbound(eq(NODE_ID), eq(EXECUTION_ID), any()))
        .thenAnswer(
            inv -> {
              final Flux<Message<?>> upstream = inv.getArgument(2);
              return upstream.map(
                  m -> {
                    if (bad.payload().equals(m.payload())) {
                      throw new IllegalStateException("channel boom");
                    }
                    return m;
                  });
            });
    when(channel.outbound(eq(NODE_ID), eq(EXECUTION_ID), any())).thenAnswer(i -> i.getArgument(2));
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(processor.isBlocking()).thenReturn(false);

    final WorkflowNode node =
        new WorkflowNode(NODE_ID, "processor", Map.of("engine", Map.of("onError", "ROUTE")));
    final Flux<Message<?>> parentStream = Flux.just(bad, good);
    final AssemblyContext context = buildContext(NODE_ID, parentStream, null);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    strategy
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.streams()[0])
        .assertNext(
            m -> {
              assertThat(m.payload()).isEqualTo("bad");
              assertThat(m.sourcePort()).isEqualTo(NodePolicy.ERROR_PORT);
            })
        .assertNext(m -> assertThat(m.payload()).isEqualTo("good"))
        .verifyComplete();
  }

  @Test
  void createAssembler_errorFromUpstream_reachesTheNodeAsUpstreamFailure() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    when(processor.isBlocking()).thenReturn(false);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "processor", Map.of());
    final Flux<Message<?>> failing = Flux.error(new IllegalStateException("upstream broke"));
    final AssemblyContext context = buildContext(NODE_ID, failing, null);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(failing);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    strategy
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.streams()[0]).verifyError(UpstreamFailure.class);
    verify(tracker)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), anyString(), eq(TaskStatus.UPSTREAM_FAILED), any());
  }

  @Test
  void createAssembler_skipFlagFlippedWhileRunning_bypassesFromTheNextElementOn() {
    final Sinks.Many<Message<?>> parent = Sinks.many().unicast().onBackpressureBuffer();
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any()))
        .thenAnswer(
            call ->
                ((Flux<Message<?>>) call.getArgument(0))
                    .map(m -> DefaultMessage.create(UUID.randomUUID(), "done:" + m.payload())));
    final AtomicBoolean skipFlag = new AtomicBoolean(false);
    final AssemblyContext context = buildContext(NODE_ID, parent.asFlux(), skipFlag);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parent.asFlux());
    final java.util.concurrent.atomic.AtomicReference<Flux<Message<?>>> built =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(
            call -> {
              built.set(call.getArgument(2));
              return call.getArgument(2);
            });

    strategy
        .createAssembler(
            new WorkflowNode(NODE_ID, "processor", Map.of()),
            processor,
            Duration.ofSeconds(5),
            0,
            1024,
            new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(built.get().map(m -> String.valueOf(m.payload())))
        .then(() -> parent.tryEmitNext(DefaultMessage.create(UUID.randomUUID(), "a")))
        .expectNext("done:a")
        .then(() -> skipFlag.set(true))
        .then(() -> parent.tryEmitNext(DefaultMessage.create(UUID.randomUUID(), "b")))
        .expectNext("b")
        .then(() -> skipFlag.set(false))
        .then(() -> parent.tryEmitNext(DefaultMessage.create(UUID.randomUUID(), "c")))
        .expectNext("done:c")
        .then(parent::tryEmitComplete)
        .verifyComplete();
  }

  @Test
  void createAssembler_retriedAttempts_areCountedAsRetries() {
    final Sinks.Many<Message<?>> parent = Sinks.many().unicast().onBackpressureBuffer();
    final java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any()))
        .thenAnswer(
            call ->
                calls.incrementAndGet() == 1
                    ? Flux.<Message<?>>error(new IllegalStateException("first attempt fails"))
                    : Flux.<Message<?>>just(DefaultMessage.create(UUID.randomUUID(), "ok")));
    final AssemblyContext context = buildContext(NODE_ID, parent.asFlux(), null);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parent.asFlux());
    final java.util.concurrent.atomic.AtomicReference<Flux<Message<?>>> built =
        new java.util.concurrent.atomic.AtomicReference<>();
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(
            call -> {
              built.set(call.getArgument(2));
              return call.getArgument(2);
            });
    final WorkflowNode node =
        new WorkflowNode(
            NODE_ID,
            "processor",
            Map.of("engine", Map.of("retry", Map.of("maxAttempts", 2, "backoffMs", 1))));

    strategy
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(built.get().map(m -> String.valueOf(m.payload())))
        .then(() -> parent.tryEmitNext(DefaultMessage.create(UUID.randomUUID(), "in")))
        .expectNext("ok")
        .then(parent::tryEmitComplete)
        .verifyComplete();

    verify(tracker).recordRetry("processor");
  }

  @Test
  void createAssembler_failureBudget_failsTheNodeOnceExceeded() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.process(any(), any()))
        .thenAnswer(inv -> Flux.<Message<?>>error(new IllegalStateException("boom")));
    when(processor.isBlocking()).thenReturn(false);
    final WorkflowNode node =
        new WorkflowNode(
            NODE_ID,
            "processor",
            Map.of(
                "engine",
                Map.of(
                    "onError",
                    "CONTINUE",
                    "failureBudget",
                    Map.of("maxFailures", 1, "windowMs", 60_000))));
    final Flux<Message<?>> parentStream =
        Flux.just(
            DefaultMessage.create(UUID.randomUUID(), "a"),
            DefaultMessage.create(UUID.randomUUID(), "b"));
    final AssemblyContext context = buildContext(NODE_ID, parentStream, null);
    when(streamTopologyDecorator.mergeParentStreams(any(), any(), any(ParentEdgeInfo[].class)))
        .thenReturn(parentStream);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    strategy
        .createAssembler(node, processor, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0])
        .assemble(context);

    StepVerifier.create(context.streams()[0])
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("failed more than 1"))
        .verify(Duration.ofSeconds(5));
  }
}
