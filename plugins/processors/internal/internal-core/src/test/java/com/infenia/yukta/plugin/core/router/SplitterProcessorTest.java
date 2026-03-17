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
package com.infenia.yukta.plugin.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class SplitterProcessorTest {

  private SplitterProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new SplitterProcessor();
  }

  @Test
  void testBasicSplit() {
    final List<String> items = Arrays.asList("apple", "banana", "cherry");
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("items", items));
    final Map<String, Object> config = Map.of("itemsPath", "payload.items");

    StepVerifier.create(
            processor
                .process(Flux.just(parent), config)
                .contextWrite(ctx -> ctx.put("nodeId", "split-node")))
        .expectNextMatches(m -> verifyChild(m, parent, "apple", 1, 3, "split-node", false))
        .expectNextMatches(m -> verifyChild(m, parent, "banana", 2, 3, "split-node", false))
        .expectNextMatches(m -> verifyChild(m, parent, "cherry", 3, 3, "split-node", true))
        .verifyComplete();
  }

  @Test
  void testHeaderMapping() {
    final List<String> items = Collections.singletonList("item1");
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of("items", items, "orderId", "ORD-123"));
    final Map<String, Object> config =
        Map.of(
            "itemsPath",
            "payload.items",
            "headerMapping",
            Map.of("parentOrder", "payload.orderId"));

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals("ORD-123", m.getMetadata().get("parentOrder"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testTechnicalHeaderPropagation() {
    final List<String> items = Collections.singletonList("item1");
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of("items", items))
            .withPriority(9)
            .withExpiration(System.currentTimeMillis() + 10000)
            .withFormatIndicator("v2")
            .withMetadata(Map.of("custom-tech", "val"));

    final Map<String, Object> config = Map.of("itemsPath", "payload.items");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals(parent.getTraceId(), m.getTraceId());
              assertEquals(9, m.getPriority());
              assertEquals(parent.getExpiration(), m.getExpiration());
              assertEquals("v2", m.getFormatIndicator());
              assertEquals("val", m.getMetadata().get("custom-tech"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStreamSplit() {
    final List<Integer> data = Arrays.asList(1, 2);
    final Stream<Integer> items = data.stream();
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("stream", items));
    final Map<String, Object> config = Map.of("itemsPath", "payload.stream");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals(1, m.getPayload());
              assertEquals(0, m.getSequenceSize());
              assertFalse(m.isLastInSequence());
              assertEquals(1, m.getSequenceNumber());
              return true;
            })
        .expectNextMatches(
            m -> {
              assertEquals(2, m.getPayload());
              assertEquals(2, m.getSequenceSize());
              assertTrue(m.isLastInSequence());
              assertEquals(2, m.getSequenceNumber());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testErrorPort() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), "not-a-map");
    final Map<String, Object> config =
        Map.of(
            "itemsPath", "payload.items",
            "errorPort", "error-out");

    StepVerifier.create(
            processor
                .process(Flux.just(parent), config)
                .contextWrite(ctx -> ctx.put("nodeId", "split-node")))
        .expectNextMatches(
            m -> {
              assertEquals("error-out", m.getSourcePort());
              assertNotNull(m.getFailureReason());
              assertTrue(m.getMessageHistory().contains("split-node"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStrictMode() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), "not-a-map");
    final Map<String, Object> config = Map.of("itemsPath", "payload.items", "strictMode", true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Splitter failed"))
        .verify();
  }

  @Test
  void testSequentialProcessing() {
    final List<Integer> items = Arrays.asList(1, 2, 3);
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("items", items));
    final Map<String, Object> config = Map.of("itemsPath", "payload.items", "parallel", false);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextCount(3)
        .verifyComplete();
  }

  private boolean verifyChild(
      Message<?> child,
      Message<?> parent,
      Object expectedPayload,
      int index,
      int total,
      String nodeId,
      boolean isLast) {
    assertEquals(expectedPayload, child.getPayload());
    assertEquals(parent.getMessageId(), child.getCorrelationId());
    assertEquals(parent.getMessageId(), child.getSequenceId());
    assertEquals(index, child.getSequenceNumber(), "Sequence number mismatch");
    assertEquals(total, child.getSequenceSize(), "Sequence size mismatch");
    assertEquals(isLast, child.isLastInSequence(), "Last in sequence flag mismatch");
    assertEquals("default", child.getSourcePort());
    assertTrue(child.getMessageHistory().contains(nodeId));
    assertFalse(child.getMessageId().equals(parent.getMessageId()));
    assertEquals(
        parent.getTimestamp(),
        child.getTimestamp(),
        "Timestamp must be propagated from parent: "
            + parent.getTimestamp()
            + " vs "
            + child.getTimestamp());
    return true;
  }

  @Test
  void testType() {
    assertEquals("SPLITTER", processor.getType());
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.getUiDesign().isPresent());
    assertTrue(processor.getUiDesign().get().html().contains("Splitter"));
  }

  @Test
  void testOutputPorts() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of()));
    assertEquals(List.of("default", "fail"), processor.getOutputPorts(Map.of("errorPort", "fail")));
  }

  @Test
  void testIteratorSplit() {
    final Iterator<Integer> items = Arrays.asList(1, 2).iterator();
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("it", items));
    final Map<String, Object> config = Map.of("itemsPath", "payload.it");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> m.getPayload().equals(1))
        .expectNextMatches(m -> m.getPayload().equals(2))
        .verifyComplete();
  }

  @Test
  void testArraySplit() {
    final String[] items = {"a", "b"};
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("arr", items));
    final Map<String, Object> config = Map.of("itemsPath", "payload.arr");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> m.getPayload().equals("a"))
        .expectNextMatches(m -> m.getPayload().equals("b"))
        .verifyComplete();
  }

  @Test
  void testSingleObjectSplit() {
    final String item = "only-one";
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("item", item));
    final Map<String, Object> config = Map.of("itemsPath", "payload.item");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals("only-one", m.getPayload());
              assertEquals(1, m.getSequenceSize());
              assertTrue(m.isLastInSequence());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testNullItemsNonStrict() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of());
    final Map<String, Object> config = Map.of("itemsPath", "payload.missing", "strictMode", false);

    StepVerifier.create(processor.process(Flux.just(parent), config)).verifyComplete();
  }

  @Test
  void testNullMappingStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of("items", Collections.singletonList(1)));
    final Map<String, Object> config =
        Map.of(
            "itemsPath",
            "payload.items",
            "headerMapping",
            Map.of("missing", "payload.none"),
            "strictMode",
            true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Mapping failed"))
        .verify();
  }

  @Test
  void testMappingErrorStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of("items", Collections.singletonList(1)));
    final Map<String, Object> config =
        Map.of(
            "itemsPath",
            "payload.items",
            "headerMapping",
            Map.of("err", "1/0"), // Division by zero in SpEL
            "strictMode",
            true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Mapping failed"))
        .verify();
  }

  @Test
  void testMappingErrorNonStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of("items", Collections.singletonList(1)));
    final Map<String, Object> config =
        Map.of(
            "itemsPath",
            "payload.items",
            "headerMapping",
            Map.of("err", "1/0"),
            "strictMode",
            false);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> !m.getMetadata().containsKey("err"))
        .verifyComplete();
  }

  @Test
  void testSplitErrorNonStrict() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), "not-a-map");
    final Map<String, Object> config = Map.of("itemsPath", "payload.items", "strictMode", false);

    StepVerifier.create(processor.process(Flux.just(parent), config)).verifyComplete();
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of("itemsPath", "items"))).verifyComplete();

    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testPrepare() {
    Map<String, Object> config =
        Map.of("itemsPath", "payload.items", "headerMapping", Map.of("h1", "payload.f1"));
    StepVerifier.create(processor.prepare(config)).verifyComplete();
  }

  @Test
  void testHandleNullItemsStrict() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of());
    final Map<String, Object> config = Map.of("itemsPath", "payload.missing", "strictMode", true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Splitter failed"))
        .verify();
  }
}
