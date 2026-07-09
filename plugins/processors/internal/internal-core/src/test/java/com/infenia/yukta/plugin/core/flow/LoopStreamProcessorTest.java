// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.Map;
import java.util.UUID;
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

@ExtendWith(MockitoExtension.class)
class LoopStreamProcessorTest {

  @Mock private ObjectProvider<PluginRegistry> registryProvider;
  @Mock private ObjectProvider<DefaultTaskTrackerService> trackerProvider;
  @Mock private PluginRegistry registry;
  @Mock private DefaultTaskTrackerService tracker;
  @Mock private ProcessorPlugin targetPlugin;

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
              Flux<Message<?>> input = invocation.getArgument(0);
              return input.flatMap(
                  msg -> {
                    int page = (int) msg.getPayload();
                    final String traceIdStr = msg.getTraceId();
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
        Map.of(
            "targetPluginId",
            targetId,
            "maxIterations",
            2,
            "exitCondition",
            "#root.payload instanceof T(Integer) && #root.payload == 2");

    final Message<?> inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectNextMatches(msg -> "Item 1".equals(msg.getPayload()))
        .expectNextMatches(msg -> "Item 2".equals(msg.getPayload()))
        .expectNextMatches(msg -> Integer.valueOf(1).equals(msg.getPayload()))
        .expectNextMatches(msg -> "Item 3".equals(msg.getPayload()))
        .expectNextMatches(msg -> "Item 4".equals(msg.getPayload()))
        .expectNextMatches(msg -> Integer.valueOf(2).equals(msg.getPayload()))
        .verifyComplete();
  }

  @Test
  void testMetadata() {
    org.junit.jupiter.api.Assertions.assertEquals("LOOP_STREAM", processor.getType());
    org.junit.jupiter.api.Assertions.assertNotNull(processor.getDescription());
    org.junit.jupiter.api.Assertions.assertNotNull(processor.getUsagePattern());
    org.junit.jupiter.api.Assertions.assertTrue(processor.getUiDesign().isPresent());
  }

  @Test
  void testPrepare() {
    StepVerifier.create(processor.prepare(Map.of("exitCondition", "true"))).verifyComplete();
  }

  @Test
  void testPluginNotFound() {
    when(registry.contains("missing")).thenReturn(false);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")),
                Map.of("targetPluginId", "missing")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testNotAProcessor() {
    when(registry.contains("trigger")).thenReturn(true);
    when(registry.get("trigger")).thenReturn(org.mockito.Mockito.mock(Plugin.class));
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")),
                Map.of("targetPluginId", "trigger")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMaxIterations() {
    final String targetId = "target";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), "val")));

    final Map<String, Object> config =
        Map.of("targetPluginId", targetId, "maxIterations", 1, "exitCondition", "false");
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testFailureStrategies() {
    final String targetId = "target";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any())).thenReturn(Flux.error(new RuntimeException("fail")));

    // SKIP
    final Map<String, Object> configSkip =
        Map.of("targetPluginId", targetId, "failureStrategy", "SKIP", "maxIterations", 1);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), configSkip))
        .expectNextCount(0)
        .verifyComplete();

    // ESCALATE
    final Map<String, Object> configEscalate =
        Map.of("targetPluginId", targetId, "failureStrategy", "ESCALATE");
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), configEscalate))
        .expectErrorMatches(e -> e.getMessage().contains("Loop execution failed"))
        .verify();

    // RETRY (only once for test)
    final Map<String, Object> configRetry =
        Map.of("targetPluginId", targetId, "failureStrategy", "RETRY", "maxIterations", 1);
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), configRetry))
        .expectNextCount(0)
        .verifyComplete();

    // ABORT
    final Map<String, Object> configAbort =
        Map.of("targetPluginId", targetId, "failureStrategy", "ABORT");
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), configAbort))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void testMaxDuration() {
    final String targetId = "target";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), "val")));

    final Map<String, Object> config =
        Map.of(
            "targetPluginId",
            targetId,
            "maxDuration",
            "PT0.1S",
            "delayInterval",
            "PT0.2S",
            "exitCondition",
            "false");
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), config))
        .expectNextCount(1) // gets the first iteration results before checking duration for next
        .verifyComplete();
  }

  @Test
  void testEmptyExitCondition() {
    final String targetId = "target";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), "val")));

    final Map<String, Object> config = Map.of("targetPluginId", targetId, "exitCondition", "");
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), config))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testMissingTracker() {
    org.mockito.Mockito.lenient().when(trackerProvider.getIfAvailable()).thenReturn(null);
    final String targetId = "target";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);
    when(targetPlugin.process(any(), any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), "val")));

    final Map<String, Object> config = Map.of("targetPluginId", targetId, "maxIterations", 1);
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), config))
        .expectNextCount(1)
        .verifyComplete();
  }
}
