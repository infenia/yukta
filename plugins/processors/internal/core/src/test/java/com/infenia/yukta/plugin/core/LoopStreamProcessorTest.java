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
package com.infenia.yukta.plugin.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
class LoopStreamProcessorTest {

  @Mock private ObjectProvider<WorkflowRegistry> registryProvider;
  @Mock private ObjectProvider<TaskTrackerService> trackerProvider;
  @Mock private WorkflowRegistry registry;
  @Mock private TaskTrackerService tracker;
  @Mock private ProcessorPlugin targetPlugin;

  @InjectMocks private LoopStreamProcessor processor;

  @BeforeEach
  void setUp() {
    when(registryProvider.getIfAvailable()).thenReturn(registry);
    when(trackerProvider.getIfAvailable()).thenReturn(tracker);
    when(tracker.appendLog(anyString(), anyString())).thenReturn(Mono.empty());
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
}
