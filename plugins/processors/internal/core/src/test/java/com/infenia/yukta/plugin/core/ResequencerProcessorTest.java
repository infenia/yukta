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

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.service.resequence.InMemoryResequencerStore;
import com.infenia.yukta.service.resequence.ResequencerStore;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ResequencerProcessorTest {

  private ResequencerProcessor processor;
  private ResequencerStore store;

  @BeforeEach
  void setUp() {
    processor = new ResequencerProcessor();
    store = new InMemoryResequencerStore();
    ((InMemoryResequencerStore) store).init();
    ReflectionTestUtils.setField(processor, "resequencerStore", store);
  }

  @Test
  void testInOrderProcessing() {
    final Map<String, Object> config = Map.of("startIndex", 1, "sequenceIdPath", "'default'");
    final Message<String> m1 =
        DefaultMessage.create(UUID.randomUUID(), "1").withHeader("sequenceNumber", 1);
    final Message<String> m2 =
        DefaultMessage.create(UUID.randomUUID(), "2").withHeader("sequenceNumber", 2);

    StepVerifier.create(
            processor
                .process(Flux.just(m1, m2), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node").put("sessionId", "session-1")))
        .consumeNextWith(
            m -> {
              assertThat(m.getPayload()).isEqualTo("1");
            })
        .consumeNextWith(
            m -> {
              assertThat(m.getPayload()).isEqualTo("2");
            })
        .verifyComplete();
  }

  @Test
  void testOutOrderProcessing() {
    final Map<String, Object> config = Map.of("startIndex", 1, "sequenceIdPath", "'default'");
    final Message<String> m1 =
        DefaultMessage.create(UUID.randomUUID(), "1").withHeader("sequenceNumber", 1);
    final Message<String> m2 =
        DefaultMessage.create(UUID.randomUUID(), "2").withHeader("sequenceNumber", 2);
    final Message<String> m3 =
        DefaultMessage.create(UUID.randomUUID(), "3").withHeader("sequenceNumber", 3);

    // Sequence: 2, 3, 1
    StepVerifier.create(
            processor
                .process(Flux.just(m2, m3, m1), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node").put("sessionId", "session-1")))
        .consumeNextWith(m -> assertThat(m.getPayload()).isEqualTo("1"))
        .consumeNextWith(m -> assertThat(m.getPayload()).isEqualTo("2"))
        .consumeNextWith(m -> assertThat(m.getPayload()).isEqualTo("3"))
        .verifyComplete();
  }

  @Test
  void testGapTimeoutProcessing() {
    final Map<String, Object> config =
        Map.of(
            "startIndex", 1, "timeoutMs", 10, "errorPort", "error", "sequenceIdPath", "'default'");
    final Message<String> m2 =
        DefaultMessage.create(UUID.randomUUID(), "2").withHeader("sequenceNumber", 2);
    final Message<String> m3 =
        DefaultMessage.create(UUID.randomUUID(), "3").withHeader("sequenceNumber", 3);

    final reactor.core.publisher.Sinks.Many<Message<?>> sink =
        reactor.core.publisher.Sinks.many().multicast().onBackpressureBuffer();

    // Send 2 and 3, wait for timeout for 1
    StepVerifier.create(
            processor
                .process(sink.asFlux(), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node").put("sessionId", "session-1")))
        .then(
            () -> {
              sink.tryEmitNext(m2);
              sink.tryEmitNext(m3);
            })
        .thenAwait(Duration.ofMillis(500))
        .consumeNextWith(
            m -> {
              assertThat(m.getPayload()).isIn("2", "3");
              assertThat(m.getSourcePort()).isEqualTo("error");
            })
        .consumeNextWith(
            m -> {
              assertThat(m.getPayload()).isIn("2", "3");
              assertThat(m.getSourcePort()).isEqualTo("error");
            })
        .thenCancel()
        .verify();
  }

  @Test
  void testLateArrivalProcessing() {
    final Map<String, Object> config =
        Map.of("startIndex", 1, "errorPort", "error", "sequenceIdPath", "'default'");
    final Message<String> m1 =
        DefaultMessage.create(UUID.randomUUID(), "1").withHeader("sequenceNumber", 1);
    final Message<String> m2 =
        DefaultMessage.create(UUID.randomUUID(), "2").withHeader("sequenceNumber", 2);
    final Message<String> m_late =
        DefaultMessage.create(UUID.randomUUID(), "late").withHeader("sequenceNumber", 1);

    StepVerifier.create(
            processor
                .process(Flux.just(m1, m2, m_late), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node").put("sessionId", "session-1")))
        .consumeNextWith(m -> assertThat(m.getPayload()).isEqualTo("1"))
        .consumeNextWith(m -> assertThat(m.getPayload()).isEqualTo("2"))
        .consumeNextWith(
            m -> {
              assertThat(m.getPayload()).isEqualTo("late");
              assertThat(m.getSourcePort()).isEqualTo("error");
              assertThat(m.getFailureReason()).isEqualTo("LATE_ARRIVAL");
            })
        .verifyComplete();
  }

  @Test
  void testDuplicateSequenceProcessing() {
    final Map<String, Object> config =
        Map.of("startIndex", 1, "errorPort", "error", "sequenceIdPath", "'default'");
    final Message<String> m2 =
        DefaultMessage.create(UUID.randomUUID(), "2").withHeader("sequenceNumber", 2);
    final Message<String> m2_dup =
        DefaultMessage.create(UUID.randomUUID(), "2-dup").withHeader("sequenceNumber", 2);

    StepVerifier.create(
            processor
                .process(Flux.just(m2, m2_dup), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node").put("sessionId", "session-1")))
        .consumeNextWith(
            m -> {
              assertThat(m.getPayload()).isEqualTo("2-dup");
              assertThat(m.getSourcePort()).isEqualTo("error");
              assertThat(m.getFailureReason()).isEqualTo("DUPLICATE_SEQUENCE_NUMBER");
            })
        .thenCancel()
        .verify();
  }

  @Test
  void testType() {
    assertThat(processor.getType()).isEqualTo("RESEQUENCER");
  }

  @Test
  void testOutputPorts() {
    assertThat(processor.getOutputPorts(Map.of())).containsExactly("default");
    assertThat(processor.getOutputPorts(Map.of("errorPort", "err")))
        .containsExactly("default", "err");
  }

  @Test
  void testUiDesign() {
    assertThat(processor.getUiDesign()).isPresent();
    assertThat(processor.getUiDesign().get().width()).isEqualTo(140);
    assertThat(processor.getUiDesign().get().height()).isEqualTo(80);
    assertThat(processor.getUiDesign().get().html()).contains("Resequencer");
  }
}
