// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
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

/** Loop predicate processor test. */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"PMD.TooManyMethods", "PMD.TooManyStaticImports"})
@NoArgsConstructor
class LoopPredicateProcessorTest {

  /** Literal exitCondition. */
  private static final String EXITCONDITION = "exitCondition";

  /** Literal failureStrategy. */
  private static final String FAILURESTRATEGY = "failureStrategy";

  /** Literal maxIterations. */
  private static final String MAXITERATIONS = "maxIterations";

  /** Literal target. */
  private static final String TARGET = "target";

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
  @InjectMocks private LoopPredicateProcessor processor;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(registryProvider.getIfAvailable()).thenReturn(registry);
    org.mockito.Mockito.lenient().when(trackerProvider.getIfAvailable()).thenReturn(tracker);
    org.mockito.Mockito.lenient()
        .when(tracker.appendLog(anyString(), anyString()))
        .thenReturn(Mono.empty());
  }

  @Test
  void testLoopStoppingViaExitCondition() {
    final String targetId = "incrementor";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);

    when(targetPlugin.process(any(), any()))
        .thenAnswer(
            invocation -> {
              final Flux<Message> input = invocation.getArgument(0);
              return input.map(
                  msg -> {
                    final int val = (int) msg.payload();
                    return DefaultMessage.create(UUID.fromString(msg.traceId()), val + 1);
                  });
            });

    final Map<String, Object> config =
        Map.of(
            TARGETPLUGINID,
            targetId,
            MAXITERATIONS,
            5,
            "maxDuration",
            "PT1M",
            EXITCONDITION,
            "#root.payload == 3");

    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.payload() == 3)
        .verifyComplete();

    verify(tracker, atLeastOnce()).appendLog(eq("e1"), anyString());
  }

  @Test
  void testLoopStoppingViaMaxIterations() {
    final String targetId = "incrementor";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);

    when(targetPlugin.process(any(), any()))
        .thenAnswer(
            invocation -> {
              final Flux<Message> input = invocation.getArgument(0);
              return input.map(
                  msg -> {
                    final int val = (int) msg.payload();
                    return DefaultMessage.create(UUID.fromString(msg.traceId()), val + 1);
                  });
            });

    final Map<String, Object> config =
        Map.of(
            TARGETPLUGINID,
            targetId,
            MAXITERATIONS,
            2,
            EXITCONDITION,
            "#root.payload == 10" // won't reach
            );

    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.payload() == 2)
        .verifyComplete();

    verify(tracker).appendLog(eq("e1"), eq("[Loop] Node: n1 - Max iterations (2)"));
  }

  @Test
  void testEscalateFailureStrategy() {
    final String targetId = "failer";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);

    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.error(new RuntimeException("Failure")));

    final Map<String, Object> config =
        Map.of(TARGETPLUGINID, targetId, FAILURESTRATEGY, "ESCALATE");

    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectErrorMatches(
            e ->
                e instanceof RunAbortedException
                    && e.getMessage().contains("Loop execution failed")
                    && "Failure".equals(e.getCause().getMessage()))
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
    when(targetPlugin.process(any(), any())).thenReturn(Flux.error(new RuntimeException("fail")));

    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                Map.of(TARGETPLUGINID, TARGET, FAILURESTRATEGY, "RETRY", MAXITERATIONS, 3)))
        .expectNextMatches(m -> TEST.equals(m.payload()))
        .verifyComplete();
    verify(targetPlugin, times(3)).process(any(), any());
  }

  @Test
  void testMetadata() {
    org.junit.jupiter.api.Assertions.assertEquals(
        "LOOP_PREDICATE", processor.getType(), "Test metadata");
    org.junit.jupiter.api.Assertions.assertNotNull(
        processor.describe().description(), "Test metadata (2)");
    org.junit.jupiter.api.Assertions.assertNotNull(
        processor.describe().usagePattern(), "Test metadata (3)");
    org.junit.jupiter.api.Assertions.assertTrue(
        processor.describe().uiDesign().isPresent(), "Test metadata (4)");
  }

  @Test
  void testPrepare() {
    StepVerifier.create(processor.prepare(Map.of(EXITCONDITION, "true"))).verifyComplete();
  }

  @Test
  void testPluginNotFound() {
    when(registry.contains("missing")).thenReturn(false);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                Map.of(TARGETPLUGINID, "missing")))
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
  void testFailureStrategies() {
    final String targetId = TARGET;
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any())).thenReturn(Flux.error(new RuntimeException("fail")));

    // SKIP
    final Map<String, Object> configSkip =
        Map.of(TARGETPLUGINID, targetId, FAILURESTRATEGY, "SKIP", MAXITERATIONS, 1);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), configSkip))
        .expectNextMatches(m -> TEST.equals(m.payload()))
        .verifyComplete();

    // RETRY (only once for test)
    final Map<String, Object> configRetry =
        Map.of(TARGETPLUGINID, targetId, FAILURESTRATEGY, "RETRY", MAXITERATIONS, 1);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)), configRetry))
        .expectNextMatches(m -> TEST.equals(m.payload()))
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
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), 0)), config))
        .expectNextMatches(m -> Integer.valueOf(1).equals(m.payload()) || VAL.equals(m.payload()))
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
        .expectNextMatches(m -> VAL.equals(m.payload()))
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
}
