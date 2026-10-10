// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
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
        .expectNextMatches(m -> verifyChild(m, parent, "apple", 1, 3, false))
        .expectNextMatches(m -> verifyChild(m, parent, "banana", 2, 3, false))
        .expectNextMatches(m -> verifyChild(m, parent, "cherry", 3, 3, true))
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
              assertEquals("ORD-123", m.metadata().get("parentOrder"));
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
              assertEquals(parent.traceId(), m.traceId());
              assertEquals(9, m.priority());
              assertEquals(parent.expiration(), m.expiration());
              assertEquals("v2", m.formatIndicator());
              assertEquals("val", m.metadata().get("custom-tech"));
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
              assertEquals(1, m.payload());
              assertEquals(0, m.sequenceSize());
              assertEquals(1, m.sequenceNumber());
              return true;
            })
        .expectNextMatches(
            m -> {
              assertEquals(2, m.payload());
              assertEquals(2, m.sequenceSize());
              assertEquals(2, m.sequenceNumber());
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
              assertEquals("error-out", m.sourcePort());
              assertNotNull(m.failureReason());
              assertTrue(m.messageHistory().isEmpty());
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
      boolean isLast) {
    assertEquals(expectedPayload, child.payload());
    assertEquals(parent.id(), child.correlationId());
    assertEquals(parent.id(), child.sequenceId());
    assertEquals(index, child.sequenceNumber(), "Sequence number mismatch");
    assertEquals(total, child.sequenceSize(), "Sequence size mismatch");
    assertEquals(
        isLast, child.sequenceNumber() == child.sequenceSize(), "Last in sequence flag mismatch");
    assertEquals("default", child.sourcePort());
    assertTrue(child.messageHistory().isEmpty());
    assertFalse(child.id().equals(parent.id()));
    assertEquals(
        parent.timestamp(),
        child.timestamp(),
        "Timestamp must be propagated from parent: "
            + parent.timestamp()
            + " vs "
            + child.timestamp());
    return true;
  }

  @Test
  void testType() {
    assertEquals("SPLITTER", processor.getType());
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.describe().uiDesign().isPresent());
    assertTrue(processor.describe().uiDesign().get().html().contains("Splitter"));
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
        .expectNextMatches(m -> m.payload().equals(1))
        .expectNextMatches(m -> m.payload().equals(2))
        .verifyComplete();
  }

  @Test
  void testArraySplit() {
    final String[] items = {"a", "b"};
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("arr", items));
    final Map<String, Object> config = Map.of("itemsPath", "payload.arr");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> m.payload().equals("a"))
        .expectNextMatches(m -> m.payload().equals("b"))
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
              assertEquals("only-one", m.payload());
              assertEquals(1, m.sequenceSize());
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
        .expectNextMatches(m -> !m.metadata().containsKey("err"))
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

  @Test
  void testExpirationAndFormatPropagateToChildren() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of("items", List.of("a")))
            .withExpiration(500L)
            .withFormatIndicator("json-v2");

    StepVerifier.create(processor.process(Flux.just(parent), Map.of("itemsPath", "payload.items")))
        .expectNextMatches(m -> m.expiration() == 500L && "json-v2".equals(m.formatIndicator()))
        .verifyComplete();
  }

  @Test
  void testNullItemsRoutedToErrorPort() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of());
    final Map<String, Object> config = Map.of("itemsPath", "payload.missing", "errorPort", "err");

    assertEquals(List.of("default", "err"), processor.getOutputPorts(config));
    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> "err".equals(m.sourcePort()) && m.failureReason() != null)
        .verifyComplete();
  }

  @Test
  void testPrepareIgnoresNonStringMappingValues() {
    final Map<String, Object> config =
        Map.of("itemsPath", "payload.items", "headerMapping", Map.of("a", 1, "b", "payload.x"));

    StepVerifier.create(processor.prepare(config)).verifyComplete();
  }

  @Test
  void testExplicitNullItemsStrictAndNonStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Collections.singletonMap("items", null));

    StepVerifier.create(processor.process(Flux.just(parent), Map.of("itemsPath", "payload.items")))
        .expectErrorMatches(e -> e.getMessage().contains("itemsPath null"))
        .verify();
    StepVerifier.create(
            processor.process(
                Flux.just(parent), Map.of("itemsPath", "payload.items", "strictMode", false)))
        .verifyComplete();
  }
}
