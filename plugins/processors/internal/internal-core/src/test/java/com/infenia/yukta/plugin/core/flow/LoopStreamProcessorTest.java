// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Loop stream processor test. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"PMD.TooManyMethods", "PMD.TooManyStaticImports"})
@NoArgsConstructor
class LoopStreamProcessorTest {

  /** Literal fail. */
  private static final String FAIL = "fail";

  /** Literal exitCondition. */
  private static final String EXITCONDITION = "exitCondition";

  /** Literal failureStrategy. */
  private static final String FAILURESTRATEGY = "failureStrategy";

  /** Literal maxIterations. */
  private static final String MAXITERATIONS = "maxIterations";

  /** Literal target. */
  private static final String TARGET = "target";

  /** Literal missing. */
  private static final String MISSING = "missing";

  /** Literal targetConfig. */
  private static final String TARGETCONFIG = "targetConfig";

  /** Literal targetPluginId. */
  private static final String TARGETPLUGINID = "targetPluginId";

  /** Literal test. */
  private static final String TEST = "test";

  /** Literal val. */
  private static final String VAL = "val";

  /** Registry provider. */
  @Mock private ObjectProvider<PluginRegistry> registryProvider;

  /** Tracker provider. */
  @Mock private ObjectProvider<DefaultTaskTrackerService> trackerProvider;

  /** Registry. */
  @Mock private PluginRegistry registry;

  /** Tracker. */
  @Mock private DefaultTaskTrackerService tracker;

  /** Target plugin. */
  @Mock private ProcessorPlugin targetPlugin;

  /** Processor. */
  @InjectMocks private LoopStreamProcessor processor;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(registryProvider.getIfAvailable()).thenReturn(registry);
    org.mockito.Mockito.lenient().when(trackerProvider.getIfAvailable()).thenReturn(tracker);
    org.mockito.Mockito.lenient()
        .when(tracker.appendLog(anyString(), anyString()))
        .thenReturn(Mono.empty());
  }

  @Test
  void testStreamingLoop() {
    final String targetId = "paginator";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);

    when(targetPlugin.process(any(), any()))
        .thenAnswer(
            invocation -> {
              final Flux<Message<?>> input = invocation.getArgument(0);
              return input.flatMap(
                  msg -> {
                    final int page = (int) msg.payload();
                    final String traceIdStr = msg.traceId();
                    final UUID traceId =
                        traceIdStr != null ? UUID.fromString(traceIdStr) : UUID.randomUUID();
                    return Flux.just(
                        DefaultMessage.create(traceId, "Item " + (page * 2 + 1)),
                        DefaultMessage.create(traceId, "Item " + (page * 2 + 2)),
                        DefaultMessage.create(traceId, page + 1) // next page state
                        );
                  });
            });

    final Map<String, Object> config =
        Map.of(TARGETPLUGINID, targetId, MAXITERATIONS, 2, EXITCONDITION, "#root.payload == 2");

    final Message<?> inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectNextMatches(msg -> "Item 1".equals(msg.payload()))
        .expectNextMatches(msg -> "Item 2".equals(msg.payload()))
        .expectNextMatches(msg -> Integer.valueOf(1).equals(msg.payload()))
        .expectNextMatches(msg -> "Item 3".equals(msg.payload()))
        .expectNextMatches(msg -> "Item 4".equals(msg.payload()))
        .expectNextMatches(msg -> Integer.valueOf(2).equals(msg.payload()))
        .verifyComplete();
  }

  @Test
  void testMetadata() {
    org.junit.jupiter.api.Assertions.assertEquals(
        "LOOP_STREAM", processor.getType(), "Test metadata");
    org.junit.jupiter.api.Assertions.assertNotNull(
        processor.describe().description(), "Test metadata (2)");
    org.junit.jupiter.api.Assertions.assertNotNull(
        processor.describe().usagePattern(), "Test metadata (3)");
    org.junit.jupiter.api.Assertions.assertTrue(
        processor.describe().uiDesign().isPresent(), "Test metadata (4)");
  }

  @Test
  void testPrepare() {
    when(registry.contains(TARGET)).thenReturn(true);
    when(registry.get(TARGET)).thenReturn(targetPlugin);
    when(targetPlugin.prepare(Map.of())).thenReturn(Mono.empty());

    StepVerifier.create(processor.prepare(Map.of(TARGETPLUGINID, TARGET, EXITCONDITION, "true")))
        .verifyComplete();
  }

  @Test
  void testPluginNotFound() {
    when(registry.contains(MISSING)).thenReturn(false);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                Map.of(TARGETPLUGINID, MISSING)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testNotProcessor() {
    when(registry.contains("trigger")).thenReturn(true);
    when(registry.get("trigger")).thenReturn(org.mockito.Mockito.mock(Plugin.class));
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                Map.of(TARGETPLUGINID, "trigger")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMaxIterations() {
    final String targetId = TARGET;
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), VAL)));

    final Map<String, Object> config =
        Map.of(TARGETPLUGINID, targetId, MAXITERATIONS, 1, EXITCONDITION, "false");
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testFailureStrategies() {
    final String targetId = TARGET;
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any())).thenReturn(Flux.error(new RuntimeException(FAIL)));

    // SKIP
    final Map<String, Object> configSkip =
        Map.of(TARGETPLUGINID, targetId, FAILURESTRATEGY, "SKIP", MAXITERATIONS, 1);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), configSkip))
        .expectNextCount(0)
        .verifyComplete();

    // ESCALATE
    final Map<String, Object> configEscalate =
        Map.of(TARGETPLUGINID, targetId, FAILURESTRATEGY, "ESCALATE");
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), configEscalate))
        .expectErrorMatches(e -> e.getMessage().contains("Loop execution failed"))
        .verify();

    // RETRY (only once for test)
    final Map<String, Object> configRetry =
        Map.of(TARGETPLUGINID, targetId, FAILURESTRATEGY, "RETRY", MAXITERATIONS, 1);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), configRetry))
        .expectNextCount(0)
        .verifyComplete();

    // ABORT
    final Map<String, Object> configAbort =
        Map.of(TARGETPLUGINID, targetId, FAILURESTRATEGY, "ABORT");
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), configAbort))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void escalateEndsTheRunWithAnAbort() {
    when(registry.contains(TARGET)).thenReturn(true);
    when(registry.get(TARGET)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any())).thenReturn(Flux.error(new RuntimeException(FAIL)));

    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                Map.of(TARGETPLUGINID, TARGET, FAILURESTRATEGY, "ESCALATE")))
        .expectErrorMatches(
            e ->
                e instanceof RunAbortedException
                    && e.getMessage().contains("Loop execution failed")
                    && FAIL.equals(e.getCause().getMessage()))
        .verify();
  }

  @Test
  void abortFromTheTargetIsNeverRetriedOrSkipped() {
    when(registry.contains(TARGET)).thenReturn(true);
    when(registry.get(TARGET)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.error(new RunAbortedException("budget used up")));

    for (final String strategy : new String[] {"RETRY", "SKIP"}) {
      StepVerifier.create(
              processor.process(
                  Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                  Map.of(TARGETPLUGINID, TARGET, FAILURESTRATEGY, strategy, MAXITERATIONS, 5)))
          .expectError(RunAbortedException.class)
          .verify();
    }
    verify(targetPlugin, times(2)).process(any(), any());
  }

  @Test
  void retryRunsTheSameInputAgainUntilMaxIterations() {
    when(registry.contains(TARGET)).thenReturn(true);
    when(registry.get(TARGET)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any())).thenReturn(Flux.error(new RuntimeException(FAIL)));

    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                Map.of(TARGETPLUGINID, TARGET, FAILURESTRATEGY, "RETRY", MAXITERATIONS, 3)))
        .verifyComplete();
    verify(targetPlugin, times(3)).process(any(), any());
  }

  @Test
  void validateConfigIsPassedOnToTheTarget() {
    final Map<String, Object> targetConfig = Map.of("k", "v");
    when(registry.contains(TARGET)).thenReturn(true);
    when(registry.get(TARGET)).thenReturn(targetPlugin);
    when(targetPlugin.validateConfig(targetConfig))
        .thenReturn(Mono.error(new IllegalArgumentException("bad target config")));

    StepVerifier.create(
            processor.validateConfig(Map.of(TARGETPLUGINID, TARGET, TARGETCONFIG, targetConfig)))
        .expectErrorMessage("bad target config")
        .verify();
  }

  @Test
  void validateInContextIsPassedOnToTheTarget() {
    final Map<String, Object> targetConfig = Map.of("k", "v");
    final WorkflowContext context = new WorkflowContext("loop", List.of(), List.of());
    when(registry.contains(TARGET)).thenReturn(true);
    when(registry.get(TARGET)).thenReturn(targetPlugin);
    when(targetPlugin.validateInContext(context, targetConfig))
        .thenReturn(Mono.error(new IllegalStateException("bad wiring")));

    StepVerifier.create(
            processor.validateInContext(
                context, Map.of(TARGETPLUGINID, TARGET, TARGETCONFIG, targetConfig)))
        .expectErrorMessage("bad wiring")
        .verify();
  }

  @Test
  void validateConfigRejectsAnUnknownTarget() {
    when(registry.contains(MISSING)).thenReturn(false);

    StepVerifier.create(processor.validateConfig(Map.of(TARGETPLUGINID, MISSING)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void initializeAndPrepareReachTheTarget() {
    final Map<String, Object> targetConfig = Map.of("k", "v");
    final Map<String, Object> config =
        Map.of(TARGETPLUGINID, TARGET, TARGETCONFIG, targetConfig, EXITCONDITION, "true");
    when(registry.contains(TARGET)).thenReturn(true);
    when(registry.get(TARGET)).thenReturn(targetPlugin);
    when(targetPlugin.initialize(targetConfig)).thenReturn(Mono.empty());
    when(targetPlugin.prepare(targetConfig)).thenReturn(Mono.empty());

    StepVerifier.create(processor.initialize(config)).verifyComplete();
    StepVerifier.create(processor.prepare(config)).verifyComplete();

    verify(targetPlugin).initialize(targetConfig);
    verify(targetPlugin).prepare(targetConfig);
  }

  @Test
  void testMaxDuration() {
    final String targetId = TARGET;
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), VAL)));

    final Map<String, Object> config =
        Map.of(
            TARGETPLUGINID,
            targetId,
            "maxDuration",
            "PT0.1S",
            "delayInterval",
            "PT0.2S",
            EXITCONDITION,
            "false");
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), config))
        .expectNextCount(1) // gets the first iteration results before checking duration for next
        .verifyComplete();
  }

  @Test
  void testEmptyExitCondition() {
    final String targetId = TARGET;
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), VAL)));

    final Map<String, Object> config = Map.of(TARGETPLUGINID, targetId, EXITCONDITION, "");
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testMissingTracker() {
    org.mockito.Mockito.lenient().when(trackerProvider.getIfAvailable()).thenReturn(null);
    final String targetId = TARGET;
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), VAL)));

    final Map<String, Object> config = Map.of(TARGETPLUGINID, targetId, MAXITERATIONS, 1);
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testNullRegistryIsRejected() {
    when(registryProvider.getIfAvailable()).thenReturn(null);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "x")),
                Map.of("targetPluginId", "t")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTargetProducingNothingKeepsLastMessage() {
    when(registry.contains("quiet")).thenReturn(true);
    when(registry.get("quiet")).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any())).thenReturn(Flux.empty());

    StepVerifier.create(
            processor
                .process(
                    Flux.just(DefaultMessage.create(UUID.randomUUID(), 0)),
                    Map.of("targetPluginId", "quiet", "maxIterations", 1))
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .verifyComplete();
  }
}
