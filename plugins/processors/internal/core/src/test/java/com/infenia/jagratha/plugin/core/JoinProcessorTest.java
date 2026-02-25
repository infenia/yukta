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
package com.infenia.jagratha.plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.service.join.JoinStore;
import com.infenia.jagratha.service.join.JoinStore.JoinResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JoinProcessorTest {

  private JoinProcessor processor;
  private JoinStore joinStore;

  @BeforeEach
  void setUp() {
    processor = new JoinProcessor();
    joinStore = mock(JoinStore.class);
    ReflectionTestUtils.setField(processor, "joinStore", joinStore);
  }

  @Test
  void testSuccessfulJoinAll() {
    final Map<String, Object> config =
        Map.of(
            "mode", "ALL",
            "expectedAncestors", List.of("NodeA", "NodeB"),
            "mergeStrategy", "ARRAY");
    final UUID traceId = UUID.randomUUID();
    final Message msgA =
        new Message(
            UUID.randomUUID(),
            traceId,
            Map.of(),
            "payloadA",
            java.time.Instant.now(),
            null,
            "NodeA");
    final Message msgB =
        new Message(
            UUID.randomUUID(),
            traceId,
            Map.of(),
            "payloadB",
            java.time.Instant.now(),
            null,
            "NodeB");

    when(joinStore.addMessage(anyString(), eq("NodeA"), eq(msgA), any()))
        .thenReturn(Mono.just(new JoinResult(JoinResult.Status.WAITING, null)));

    when(joinStore.addMessage(anyString(), eq("NodeB"), eq(msgB), any()))
        .thenReturn(
            Mono.just(
                new JoinResult(JoinResult.Status.COMPLETED, Map.of("NodeA", msgA, "NodeB", msgB))));

    StepVerifier.create(
            processor
                .process(Flux.just(msgA, msgB), config)
                .contextWrite(ctx -> ctx.put("nodeId", "joinNode").put("sessionId", "sess1")))
        .expectNextMatches(
            m -> {
              List<?> payload = (List<?>) m.payload();
              return payload.size() == 2
                  && payload.contains("payloadA")
                  && payload.contains("payloadB");
            })
        .verifyComplete();
  }

  @Test
  void testLateArrival() {
    final Map<String, Object> config =
        Map.of(
            "mode", "ANY",
            "latePort", "late");
    final UUID traceId = UUID.randomUUID();
    final Message msg1 =
        new Message(
            UUID.randomUUID(), traceId, Map.of(), "p1", java.time.Instant.now(), null, "Node1");
    final Message msg2 =
        new Message(
            UUID.randomUUID(), traceId, Map.of(), "p2", java.time.Instant.now(), null, "Node2");

    when(joinStore.addMessage(anyString(), any(), eq(msg1), any()))
        .thenReturn(Mono.just(new JoinResult(JoinResult.Status.COMPLETED, Map.of("Node1", msg1))));

    when(joinStore.addMessage(anyString(), any(), eq(msg2), any()))
        .thenReturn(Mono.just(new JoinResult(JoinResult.Status.LATE_ARRIVAL, null)));

    StepVerifier.create(
            processor
                .process(Flux.just(msg1, msg2), config)
                .contextWrite(ctx -> ctx.put("nodeId", "joinNode").put("sessionId", "sess1")))
        .expectNextMatches(m -> m.sourcePort() == null && List.of("p1").equals(m.payload()))
        .expectNextMatches(m -> "late".equals(m.sourcePort()) && "p2".equals(m.payload()))
        .verifyComplete();
  }

  @Test
  void testObjectMerge() {
    final Map<String, Object> config =
        Map.of(
            "mode", "ALL",
            "expectedAncestors", List.of("NodeA", "NodeB"),
            "mergeStrategy", "OBJECT_MERGE");
    final UUID traceId = UUID.randomUUID();
    final Message msgA =
        new Message(
            UUID.randomUUID(),
            traceId,
            Map.of(),
            Map.of("a", 1, "common", "fromA"),
            java.time.Instant.now(),
            null,
            "NodeA");
    final Message msgB =
        new Message(
            UUID.randomUUID(),
            traceId,
            Map.of(),
            Map.of("b", 2, "common", "fromB"),
            java.time.Instant.now(),
            null,
            "NodeB");

    when(joinStore.addMessage(anyString(), any(), any(), any()))
        .thenReturn(
            Mono.just(
                new JoinResult(JoinResult.Status.COMPLETED, Map.of("NodeA", msgA, "NodeB", msgB))));

    StepVerifier.create(
            processor
                .process(Flux.just(msgB), config) // Only one msg needed to trigger mock
                .contextWrite(ctx -> ctx.put("nodeId", "joinNode").put("sessionId", "sess1")))
        .expectNextMatches(
            m -> {
              Map<?, ?> payload = (Map<?, ?>) m.payload();
              return payload.get("a").equals(1)
                  && payload.get("b").equals(2)
                  && payload.get("common").equals("fromB");
            })
        .verifyComplete();
  }

  @Test
  void testCustomCount() {
    final Map<String, Object> config =
        Map.of(
            "mode", "CUSTOM_COUNT",
            "count", 1,
            "mergeStrategy", "LATEST");
    final UUID traceId = UUID.randomUUID();
    final Message msg1 =
        new Message(
            UUID.randomUUID(), traceId, Map.of(), "p1", java.time.Instant.now(), null, "Node1");

    when(joinStore.addMessage(anyString(), any(), any(), any()))
        .thenReturn(Mono.just(new JoinResult(JoinResult.Status.COMPLETED, Map.of("Node1", msg1))));

    StepVerifier.create(
            processor
                .process(Flux.just(msg1), config)
                .contextWrite(ctx -> ctx.put("nodeId", "joinNode").put("sessionId", "sess1")))
        .expectNextMatches(m -> "p1".equals(m.payload()))
        .verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("join", processor.getType());
  }

  // Helper for mockito
  private <T> T eq(T value) {
    return org.mockito.ArgumentMatchers.eq(value);
  }
}
