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
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.exception.RunAbortedException;
import com.infenia.yukta.plugin.gateway.ExecutionLog;
import com.infenia.yukta.plugin.gateway.PluginLookup;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
  @Mock private ObjectProvider<PluginLookup> registryProvider;

  /** Tracker provider. */
  @Mock private ObjectProvider<ExecutionLog> trackerProvider;

  /** Registry. */
  @Mock private PluginLookup registry;

  /** Tracker. */
  @Mock private ExecutionLog tracker;

  /** Target plugin. */
  @Mock private ProcessorPlugin targetPlugin;

  /** Processor. */
  private LoopPredicateProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new LoopPredicateProcessor(registryProvider, trackerProvider);
    org.mockito.Mockito.lenient().when(registryProvider.getIfAvailable()).thenReturn(registry);
    org.mockito.Mockito.lenient().when(trackerProvider.getIfAvailable()).thenReturn(tracker);
    org.mockito.Mockito.lenient()
        .when(tracker.append(anyString(), anyString()))
        .thenReturn(Mono.empty());
  }

  @Test
  void testLoopStoppingViaExitCondition() {
    final String targetId = "incrementor";
    when(registry.find(targetId)).thenReturn(Optional.<Plugin>of(targetPlugin));

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

    verify(tracker, atLeastOnce()).append(eq("e1"), anyString());
  }

  @Test
  void testLoopStoppingViaMaxIterations() {
    final String targetId = "incrementor";
    when(registry.find(targetId)).thenReturn(Optional.<Plugin>of(targetPlugin));

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

    verify(tracker).append(eq("e1"), eq("[Loop] Node: n1 - Max iterations (2)"));
  }

  @Test
  void testEscalateFailureStrategy() {
    final String targetId = "failer";
    when(registry.find(targetId)).thenReturn(Optional.<Plugin>of(targetPlugin));

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
    when(registry.find(TARGET)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
    when(registry.find(TARGET)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
  void validateConfigIsPassedOnToTheTarget() {
    final Map<String, Object> targetConfig = Map.of("k", "v");
    when(registry.find(TARGET)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
    final WorkflowContext context =
        new WorkflowContext("loop", java.util.List.of(), java.util.List.of());
    when(registry.find(TARGET)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
    when(registry.find(MISSING)).thenReturn(Optional.empty());

    StepVerifier.create(processor.validateConfig(Map.of(TARGETPLUGINID, MISSING)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void initializeAndPrepareReachTheTarget() {
    final Map<String, Object> targetConfig = Map.of("k", "v");
    final Map<String, Object> config =
        Map.of(TARGETPLUGINID, TARGET, TARGETCONFIG, targetConfig, EXITCONDITION, "true");
    when(registry.find(TARGET)).thenReturn(Optional.<Plugin>of(targetPlugin));
    when(targetPlugin.initialize(targetConfig)).thenReturn(Mono.empty());
    when(targetPlugin.prepare(targetConfig)).thenReturn(Mono.empty());

    StepVerifier.create(processor.initialize(config)).verifyComplete();
    StepVerifier.create(processor.prepare(config)).verifyComplete();

    verify(targetPlugin).initialize(targetConfig);
    verify(targetPlugin).prepare(targetConfig);
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
    when(registry.find(TARGET)).thenReturn(Optional.<Plugin>of(targetPlugin));
    when(targetPlugin.prepare(Map.of())).thenReturn(Mono.empty());

    StepVerifier.create(processor.prepare(Map.of(TARGETPLUGINID, TARGET, EXITCONDITION, "true")))
        .verifyComplete();
  }

  @Test
  void testPluginNotFound() {
    when(registry.find(MISSING)).thenReturn(Optional.empty());
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), TEST)),
                Map.of(TARGETPLUGINID, MISSING)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testNotProcessor() {
    when(registry.find("trigger"))
        .thenReturn(Optional.<Plugin>of(org.mockito.Mockito.mock(Plugin.class)));
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
    when(registry.find(targetId)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
    when(registry.find(targetId)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
    when(registry.find(targetId)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
    when(registry.find(targetId)).thenReturn(Optional.<Plugin>of(targetPlugin));
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
