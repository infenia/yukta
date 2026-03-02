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
package com.infenia.yukta.plugin.core.flow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowRegistry;
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
class LoopPredicateProcessorTest {

  @Mock private ObjectProvider<WorkflowRegistry> registryProvider;
  @Mock private ObjectProvider<TaskTrackerService> trackerProvider;
  @Mock private WorkflowRegistry registry;
  @Mock private TaskTrackerService tracker;
  @Mock private ProcessorPlugin targetPlugin;

  @InjectMocks private LoopPredicateProcessor processor;

  @BeforeEach
  void setUp() {
    when(registryProvider.getIfAvailable()).thenReturn(registry);
    when(trackerProvider.getIfAvailable()).thenReturn(tracker);
    when(tracker.appendLog(anyString(), anyString())).thenReturn(Mono.empty());
  }

  @Test
  void testLoopStoppingViaExitCondition() {
    final String targetId = "incrementor";
    when(registry.contains(targetId)).thenReturn(true);
    when(registry.get(targetId)).thenReturn(targetPlugin);

    when(targetPlugin.process(any(), any()))
        .thenAnswer(
            invocation -> {
              Flux<Message> input = invocation.getArgument(0);
              return input.map(
                  msg -> {
                    int val = (int) msg.getPayload();
                    return DefaultMessage.create(UUID.fromString(msg.getTraceId()), val + 1);
                  });
            });

    final Map<String, Object> config =
        Map.of(
            "targetPluginId",
            targetId,
            "maxIterations",
            5,
            "maxDuration",
            "PT1M",
            "exitCondition",
            "#root.payload == 3");

    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.getPayload() == 3)
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
              Flux<Message> input = invocation.getArgument(0);
              return input.map(
                  msg -> {
                    int val = (int) msg.getPayload();
                    return DefaultMessage.create(UUID.fromString(msg.getTraceId()), val + 1);
                  });
            });

    final Map<String, Object> config =
        Map.of(
            "targetPluginId",
            targetId,
            "maxIterations",
            2,
            "exitCondition",
            "#root.payload == 10" // won't reach
            );

    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectNextMatches(msg -> (int) msg.getPayload() == 2)
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
        Map.of("targetPluginId", targetId, "failureStrategy", "ESCALATE");

    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), 0);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("executionId", "e1", "nodeId", "n1")))
        .expectErrorMatches(e -> e.getMessage().contains("WorkflowExecutionException"))
        .verify();
  }
}
