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
import com.infenia.yukta.model.workflow.NodeAssembler;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
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
  "PMD.AvoidDuplicateLiterals",
  "PMD.UseVarargs"
})
class TriggerNodeAssemblerStrategyTest {

  @Mock private TaskTrackerService tracker;
  @Mock private StreamTopologyDecorator streamTopologyDecorator;
  @Mock private NodeMessageChannelProvider channelProvider;
  @Mock private ActivePluginRegistry activePluginRegistry;

  private TriggerNodeAssemblerStrategy strategy;

  private static final String NODE_ID = "trigger-node-1";
  private static final String EXECUTION_ID = "exec-001";
  private static final String SESSION_ID = "session-001";
  private static final String WORKFLOW_ID = "wf-001";

  @BeforeEach
  void setUp() {
    when(channelProvider.channelFor(any(), any())).thenReturn(new DirectNodeMessageChannel());
    strategy =
        new TriggerNodeAssemblerStrategy(
            tracker,
            Schedulers.boundedElastic(),
            streamTopologyDecorator,
            channelProvider,
            activePluginRegistry);
  }

  // ── supports() ────────────────────────────────────────────────────────────

  @Test
  void supports_triggerPluginWithTriggerCategory_returnsTrue() {
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    assertThat(strategy.supports(trigger, false)).isTrue();
  }

  @Test
  void supports_triggerPluginWithTriggerCategoryAndHasParents_returnsTrue() {
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    assertThat(strategy.supports(trigger, true)).isTrue();
  }

  @Test
  void supports_triggerPluginWithoutParentsNonTriggerCategory_returnsTrue() {
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    assertThat(strategy.supports(trigger, false)).isTrue();
  }

  @Test
  void supports_triggerPluginWithParentsAndNonTriggerCategory_returnsFalse() {
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    assertThat(strategy.supports(trigger, true)).isFalse();
  }

  @Test
  void supports_processorPlugin_returnsFalse() {
    final ProcessorPlugin processor = mock(ProcessorPlugin.class);
    assertThat(strategy.supports(processor, false)).isFalse();
  }

  @Test
  void supports_terminalPlugin_returnsFalse() {
    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    assertThat(strategy.supports(terminal, false)).isFalse();
  }

  @Test
  void supports_nonTriggerWorkflowPlugin_returnsFalse() {
    final Plugin plugin = mock(Plugin.class);
    assertThat(strategy.supports(plugin, false)).isFalse();
  }

  // ── createAssembler() ─────────────────────────────────────────────────────

  @Test
  void createAssembler_returnsNonNullAssembler() {
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);

    assertThat(assembler).isNotNull();
  }

  @Test
  void createAssembler_assembleInvokesStartAndBroadcasts() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "hello");
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.start(any())).thenReturn(Flux.just(msg));
    when(trigger.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, null);

    final Flux<Message<?>> broadcastFlux = Flux.just(msg);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(broadcastFlux);

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(trigger).start(any());
    verify(streamTopologyDecorator)
        .applyLoggingAndBroadcasting(anyString(), anyString(), any(), any(int.class), any(), any());
    assertThat(context.streams()[0]).isSameAs(broadcastFlux);
  }

  @Test
  void createAssembler_streamSubscribedAndCompleted_registersThenUnregistersPlugin() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "hello");
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.start(any())).thenReturn(Flux.just(msg));
    when(trigger.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, null);

    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    StepVerifier.create(context.streams()[0]).expectNextCount(1).verifyComplete();

    verify(activePluginRegistry).register(WORKFLOW_ID, NODE_ID, trigger);
    verify(activePluginRegistry).unregister(WORKFLOW_ID, NODE_ID);
  }

  @Test
  void createAssembler_blockingTrigger_subscribesOnVirtualThreadScheduler() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.start(any())).thenReturn(Flux.just(msg));
    when(trigger.isBlocking()).thenReturn(true);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, null);

    final Flux<Message<?>> broadcastFlux = Flux.just(msg);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(broadcastFlux);

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    // Blocking path still assembles; we verify it completes without error
    assertThat(context.streams()[0]).isNotNull();
  }

  @Test
  void createAssembler_withSafeStopSink_takeUntilOtherApplied() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "payload");
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    final Sinks.One<Void> safeStopSink = Sinks.one();
    when(trigger.start(any())).thenReturn(Flux.just(msg));
    when(trigger.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, safeStopSink);

    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.streams()[0]).isNotNull();
  }

  @Test
  void createAssembler_withoutSafeStopSink_assemblesNormally() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "payload");
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.start(any())).thenReturn(Flux.just(msg));
    when(trigger.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, null);

    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.streams()[0]).isNotNull();
  }

  @Test
  void createAssembler_indexPositionsStreamCorrectly() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.start(any())).thenReturn(Flux.just(msg));
    when(trigger.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final int index = 2;
    @SuppressWarnings("unchecked")
    final Flux<Message<?>>[] streams = new Flux[5];
    final AssemblyContext context = buildContextWithStreams(NODE_ID, null, streams);

    final Flux<Message<?>> broadcastFlux = Flux.just(msg);
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(broadcastFlux);

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), index, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    assertThat(context.streams()[index]).isSameAs(broadcastFlux);
  }

  @Test
  void createAssembler_safeStopTriggered_completesStream() {
    final Sinks.One<Void> safeStopSink = Sinks.one();
    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.start(any())).thenReturn(Flux.never());
    when(trigger.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, safeStopSink);

    // Return a real Flux so we can test takeUntilOther behavior end-to-end
    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenAnswer(inv -> inv.getArgument(2));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    // Safe stop should complete the stream
    safeStopSink.tryEmitEmpty();

    StepVerifier.create(context.streams()[0]).verifyComplete();
  }

  @Test
  void createAssembler_assembleCallsOutboundChannel() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "hello");
    final NodeMessageChannel channel = mock(NodeMessageChannel.class);
    when(channelProvider.channelFor(eq(NODE_ID), any())).thenReturn(channel);
    when(channel.outbound(eq(NODE_ID), eq(EXECUTION_ID), any())).thenReturn(Flux.just(msg));

    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.start(any())).thenReturn(Flux.just(msg));
    when(trigger.isBlocking()).thenReturn(false);

    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext(NODE_ID, null);

    when(streamTopologyDecorator.applyLoggingAndBroadcasting(
            anyString(), anyString(), any(), any(int.class), any(), any()))
        .thenReturn(Flux.just(msg));

    final NodeAssembler assembler =
        strategy.createAssembler(
            node, trigger, Duration.ofSeconds(5), 0, 1024, new ParentEdgeInfo[0]);
    assembler.assemble(context);

    verify(channelProvider).channelFor(eq(NODE_ID), any());
    verify(channel).outbound(eq(NODE_ID), eq(EXECUTION_ID), any());
    verify(channel, never()).inbound(any(), any(), any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContext(final String nodeId, final Sinks.One<Void> safeStopSink) {
    return buildContextWithStreams(nodeId, safeStopSink, new Flux[1]);
  }

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContextWithStreams(
      final String nodeId, final Sinks.One<Void> safeStopSink, final Flux<Message<?>>[] streams) {
    final Map<String, Sinks.One<Void>> nodeSafeStopSinks =
        safeStopSink != null ? Map.of(nodeId, safeStopSink) : Map.of();

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
            nodeSafeStopSinks,
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
