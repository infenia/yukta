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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.service.aggregate.AggregateStore;
import com.infenia.yukta.service.aggregate.AggregateStore.AggregateResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

class AggregatorProcessorTest {

  private AggregatorProcessor processor;
  private AggregateStore aggregateStore;

  @BeforeEach
  void setUp() {
    processor = new AggregatorProcessor();
    aggregateStore = mock(AggregateStore.class);
    ReflectionTestUtils.setField(processor, "aggregateStore", aggregateStore);
    when(aggregateStore.flushAll(anyString())).thenReturn(Flux.empty());
    when(aggregateStore.flushAll()).thenReturn(Flux.empty());
    when(aggregateStore.getAsyncResults()).thenReturn(Flux.empty());
  }

  @Test
  void testSuccessfulAggregation() {
    final Map<String, Object> config =
        Map.of(
            "groupBy", "payload.userId",
            "window", Map.of("type", "COUNT", "size", 2),
            "aggregation", Map.of("type", "SUM", "field", "payload.amount"));

    final Message<?> msg1 =
        DefaultMessage.create(UUID.randomUUID(), Map.of("userId", "u1", "amount", 10.0));
    final Message<?> msg2 =
        DefaultMessage.create(UUID.randomUUID(), Map.of("userId", "u1", "amount", 20.0));

    when(aggregateStore.addValue(anyString(), any(), any(), any()))
        .thenReturn(
            Mono.just(new AggregateResult(AggregateResult.Status.WAITING, null, "k1", msg1)))
        .thenReturn(
            Mono.just(new AggregateResult(AggregateResult.Status.COMPLETED, 30.0, "k1", msg2)));

    when(aggregateStore.getAsyncResults()).thenReturn(Flux.empty());

    StepVerifier.create(
            processor
                .process(Flux.just(msg1, msg2), config)
                .contextWrite(ctx -> ctx.put("nodeId", "agg").put("sessionId", "sess1")))
        .expectNextMatches(m -> (Double) m.getPayload() == 30.0)
        .verifyComplete();
  }

  @Test
  void testAsyncResults() {
    final Map<String, Object> config =
        Map.of(
            "groupBy", "payload.userId",
            "window", Map.of("type", "TIME", "durationMs", 1000),
            "aggregation", Map.of("type", "SUM", "field", "payload.amount"));

    final Message<?> msg1 =
        DefaultMessage.create(UUID.randomUUID(), Map.of("userId", "u1", "amount", 10.0));

    when(aggregateStore.addValue(anyString(), any(), any(), any()))
        .thenReturn(
            Mono.just(
                new AggregateResult(AggregateResult.Status.WAITING, null, "sess1:agg:u1", msg1)));

    final AggregateResult asyncRes =
        new AggregateResult(AggregateResult.Status.COMPLETED, 10.0, "sess1:agg:u1", msg1);
    when(aggregateStore.getAsyncResults()).thenReturn(Flux.just(asyncRes));

    final TestPublisher<Message<?>> input = TestPublisher.create();

    StepVerifier.create(
            processor
                .process(input.flux(), config)
                .contextWrite(ctx -> ctx.put("nodeId", "agg").put("sessionId", "sess1")))
        .then(() -> input.next(msg1))
        .expectNextMatches(m -> (Double) m.getPayload() == 10.0)
        .then(input::complete)
        .verifyComplete();
  }

  @Test
  void testEviction() {
    final Map<String, Object> config =
        Map.of(
            "groupBy",
            "payload.userId",
            "window",
            Map.of("type", "COUNT", "size", 10),
            "aggregation",
            Map.of("type", "SUM", "field", "payload.amount"),
            "maxPendingWindows",
            1);

    final Message<?> msg1 =
        DefaultMessage.create(UUID.randomUUID(), Map.of("userId", "u1", "amount", 10.0));

    when(aggregateStore.addValue(anyString(), any(), any(), any()))
        .thenReturn(
            Mono.just(
                new AggregateResult(AggregateResult.Status.WAITING, null, "sess1:agg:u1", msg1)));

    final AggregateResult evicted =
        new AggregateResult(AggregateResult.Status.EVICTED, 5.0, "sess1:agg:old", msg1);
    when(aggregateStore.getAsyncResults()).thenReturn(Flux.just(evicted));

    final TestPublisher<Message<?>> input = TestPublisher.create();

    StepVerifier.create(
            processor
                .process(input.flux(), config)
                .contextWrite(ctx -> ctx.put("nodeId", "agg").put("sessionId", "sess1")))
        .then(() -> input.next(msg1))
        .expectNextMatches(m -> (Double) m.getPayload() == 5.0)
        .then(input::complete)
        .verifyComplete();
  }

  @Test
  void testValidateConfig() {
    Map<String, Object> config = Map.of();
    StepVerifier.create(processor.validateConfig(config)).expectError().verify();

    config = Map.of("groupBy", "p.id");
    StepVerifier.create(processor.validateConfig(config)).expectError().verify();

    config =
        Map.of(
            "groupBy", "p.id",
            "window", Map.of("type", "COUNT"),
            "aggregation", Map.of("type", "SUM"));
    StepVerifier.create(processor.validateConfig(config)).verifyComplete();
  }

  @Test
  void testTerminationAndFinalFlush() {
    final Map<String, Object> config =
        Map.of(
            "groupBy", "payload.userId",
            "window", Map.of("type", "COUNT", "size", 10),
            "aggregation", Map.of("type", "SUM", "field", "payload.amount"));

    final Message<?> msg1 =
        DefaultMessage.create(UUID.randomUUID(), Map.of("userId", "u1", "amount", 10.0));

    when(aggregateStore.addValue(anyString(), any(), any(), any()))
        .thenReturn(
            Mono.just(
                new AggregateResult(AggregateResult.Status.WAITING, null, "sess1:agg:u1", msg1)));

    when(aggregateStore.getAsyncResults()).thenReturn(Flux.never());

    when(aggregateStore.flushAll(anyString()))
        .thenReturn(
            Flux.just(
                new AggregateResult(AggregateResult.Status.COMPLETED, 10.0, "sess1:agg:u1", msg1)));

    StepVerifier.create(
            processor
                .process(Flux.just(msg1), config)
                .contextWrite(ctx -> ctx.put("nodeId", "agg").put("sessionId", "sess1")))
        .expectNextMatches(m -> (Double) m.getPayload() == 10.0)
        .verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("AGGREGATOR", processor.getType());
  }
}
