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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Splitter processor test. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.UnitTestAssertionsShouldIncludeMessage",
  "PMD.UseConcurrentHashMap"
})
@NoArgsConstructor
class SplitterProcessorTest {

  /** Literal default. */
  private static final String DEFAULT = "default";

  /** Literal err. */
  private static final String ERR = "err";

  /** Literal headerMapping. */
  private static final String HEADERMAPPING = "headerMapping";

  /** Literal items. */
  private static final String ITEMS = "items";

  /** Literal itemsPath. */
  private static final String ITEMSPATH = "itemsPath";

  /** Literal payload.items. */
  private static final String PAYLOAD_ITEMS = "payload.items";

  /** Literal strictMode. */
  private static final String STRICTMODE = "strictMode";

  /** Processor. */
  private SplitterProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new SplitterProcessor();
  }

  @Test
  void testBasicSplit() {
    final List<String> items = Arrays.asList("apple", "banana", "cherry");
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, items));
    final Map<String, Object> config = Map.of(ITEMSPATH, PAYLOAD_ITEMS);

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
        DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, items, "orderId", "ORD-123"));
    final Map<String, Object> config =
        Map.of(ITEMSPATH, PAYLOAD_ITEMS, HEADERMAPPING, Map.of("parentOrder", "payload.orderId"));

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals("ORD-123", m.metadata().get("parentOrder"), "Test header mapping");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testTechnicalHeaderPropagation() {
    final List<String> items = Collections.singletonList("item1");
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, items))
            .withPriority(9)
            .withExpiration(System.currentTimeMillis() + 10_000)
            .withFormatIndicator("v2")
            .withMetadata(Map.of("custom-tech", "val"));

    final Map<String, Object> config = Map.of(ITEMSPATH, PAYLOAD_ITEMS);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals(parent.traceId(), m.traceId(), "Test technical header propagation");
              assertEquals(9, m.priority(), "Test technical header propagation (2)");
              assertEquals(
                  parent.expiration(), m.expiration(), "Test technical header propagation (3)");
              assertEquals("v2", m.formatIndicator(), "Test technical header propagation (4)");
              assertEquals(
                  "val", m.metadata().get("custom-tech"), "Test technical header propagation (5)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStreamSplit() {
    final List<Integer> data = Arrays.asList(1, 2);
    final Stream<Integer> items = data.stream();
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("stream", items));
    final Map<String, Object> config = Map.of(ITEMSPATH, "payload.stream");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals(1, m.payload(), "Test stream split");
              assertEquals(0, m.sequenceSize(), "Test stream split (2)");
              assertEquals(1, m.sequenceNumber(), "Test stream split (3)");
              return true;
            })
        .expectNextMatches(
            m -> {
              assertEquals(2, m.payload(), "Test stream split (4)");
              assertEquals(2, m.sequenceSize(), "Test stream split (5)");
              assertEquals(2, m.sequenceNumber(), "Test stream split (6)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testErrorPort() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), "not-a-map");
    final Map<String, Object> config = Map.of(ITEMSPATH, PAYLOAD_ITEMS, "errorPort", "error-out");

    StepVerifier.create(
            processor
                .process(Flux.just(parent), config)
                .contextWrite(ctx -> ctx.put("nodeId", "split-node")))
        .expectNextMatches(
            m -> {
              assertEquals("error-out", m.sourcePort(), "Test error port");
              assertNotNull(m.failureReason(), "Test error port (2)");
              assertTrue(m.messageHistory().isEmpty(), "Test error port (3)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStrictMode() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), "not-a-map");
    final Map<String, Object> config = Map.of(ITEMSPATH, PAYLOAD_ITEMS, STRICTMODE, true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Splitter failed"))
        .verify();
  }

  @Test
  void testSequentialProcessing() {
    final List<Integer> items = Arrays.asList(1, 2, 3);
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, items));
    final Map<String, Object> config = Map.of(ITEMSPATH, PAYLOAD_ITEMS, "parallel", false);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextCount(3)
        .verifyComplete();
  }

  private boolean verifyChild(
      final Message<?> child,
      final Message<?> parent,
      final Object expectedPayload,
      final int index,
      final int total,
      final boolean isLast) {
    assertEquals(expectedPayload, child.payload(), "Test sequential processing");
    assertEquals(parent.id(), child.correlationId(), "Test sequential processing (2)");
    assertEquals(parent.id(), child.sequenceId(), "Test sequential processing (3)");
    assertEquals(index, child.sequenceNumber(), "Sequence number mismatch");
    assertEquals(total, child.sequenceSize(), "Sequence size mismatch");
    assertEquals(
        isLast, child.sequenceNumber() == child.sequenceSize(), "Last in sequence flag mismatch");
    assertEquals(DEFAULT, child.sourcePort(), "Test sequential processing (4)");
    assertTrue(child.messageHistory().isEmpty(), "Test sequential processing (5)");
    assertFalse(child.id().equals(parent.id()), "Test sequential processing (6)");
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
    assertEquals("SPLITTER", processor.getType(), "Test type");
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.describe().uiDesign().isPresent(), "Test ui design");
    assertTrue(
        processor.describe().uiDesign().get().html().contains("Splitter"), "Test ui design (2)");
  }

  @Test
  void testOutputPorts() {
    assertEquals(List.of(DEFAULT), processor.getOutputPorts(Map.of()), "Test output ports");
    assertEquals(
        List.of(DEFAULT, "fail"),
        processor.getOutputPorts(Map.of("errorPort", "fail")),
        "Test output ports (2)");
  }

  @Test
  void testIteratorSplit() {
    final Iterator<Integer> items = Arrays.asList(1, 2).iterator();
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("it", items));
    final Map<String, Object> config = Map.of(ITEMSPATH, "payload.it");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> m.payload().equals(1))
        .expectNextMatches(m -> m.payload().equals(2))
        .verifyComplete();
  }

  @Test
  void testArraySplit() {
    final String[] items = {"a", "b"};
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("arr", items));
    final Map<String, Object> config = Map.of(ITEMSPATH, "payload.arr");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> "a".equals(m.payload()))
        .expectNextMatches(m -> "b".equals(m.payload()))
        .verifyComplete();
  }

  @Test
  void testSingleObjectSplit() {
    final String item = "only-one";
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of("item", item));
    final Map<String, Object> config = Map.of(ITEMSPATH, "payload.item");

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(
            m -> {
              assertEquals("only-one", m.payload(), "Test single object split");
              assertEquals(1, m.sequenceSize(), "Test single object split (2)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testNullItemsNonStrict() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of());
    final Map<String, Object> config = Map.of(ITEMSPATH, "payload.missing", STRICTMODE, false);

    StepVerifier.create(processor.process(Flux.just(parent), config)).verifyComplete();
  }

  @Test
  void testNullMappingStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, Collections.singletonList(1)));
    final Map<String, Object> config =
        Map.of(
            ITEMSPATH,
            PAYLOAD_ITEMS,
            HEADERMAPPING,
            Map.of("missing", "payload.none"),
            STRICTMODE,
            true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Mapping failed"))
        .verify();
  }

  @Test
  void testMappingErrorStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, Collections.singletonList(1)));
    final Map<String, Object> config =
        Map.of(
            ITEMSPATH,
            PAYLOAD_ITEMS,
            HEADERMAPPING,
            Map.of(ERR, "1/0"), // Division by zero in SpEL
            STRICTMODE,
            true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Mapping failed"))
        .verify();
  }

  @Test
  void testMappingErrorNonStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, Collections.singletonList(1)));
    final Map<String, Object> config =
        Map.of(ITEMSPATH, PAYLOAD_ITEMS, HEADERMAPPING, Map.of(ERR, "1/0"), STRICTMODE, false);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> !m.metadata().containsKey(ERR))
        .verifyComplete();
  }

  @Test
  void testSplitErrorNonStrict() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), "not-a-map");
    final Map<String, Object> config = Map.of(ITEMSPATH, PAYLOAD_ITEMS, STRICTMODE, false);

    StepVerifier.create(processor.process(Flux.just(parent), config)).verifyComplete();
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of(ITEMSPATH, ITEMS))).verifyComplete();

    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testPrepare() {
    final Map<String, Object> config =
        Map.of(ITEMSPATH, PAYLOAD_ITEMS, HEADERMAPPING, Map.of("h1", "payload.f1"));
    StepVerifier.create(processor.prepare(config)).verifyComplete();
  }

  @Test
  void testHandleNullItemsStrict() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of());
    final Map<String, Object> config = Map.of(ITEMSPATH, "payload.missing", STRICTMODE, true);

    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectErrorMatches(e -> e.getMessage().contains("Splitter failed"))
        .verify();
  }

  @Test
  void testExpirationAndFormatPropagateToChildren() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of(ITEMS, List.of("a")))
            .withExpiration(500L)
            .withFormatIndicator("json-v2");

    StepVerifier.create(processor.process(Flux.just(parent), Map.of(ITEMSPATH, PAYLOAD_ITEMS)))
        .expectNextMatches(m -> m.expiration() == 500L && "json-v2".equals(m.formatIndicator()))
        .verifyComplete();
  }

  @Test
  void testNullItemsRoutedToErrorPort() {
    final Message<?> parent = DefaultMessage.create(UUID.randomUUID(), Map.of());
    final Map<String, Object> config = Map.of(ITEMSPATH, "payload.missing", "errorPort", ERR);

    assertEquals(
        List.of(DEFAULT, ERR),
        processor.getOutputPorts(config),
        "Test null items routed to error port");
    StepVerifier.create(processor.process(Flux.just(parent), config))
        .expectNextMatches(m -> ERR.equals(m.sourcePort()) && m.failureReason() != null)
        .verifyComplete();
  }

  @Test
  void testPrepareIgnoresNonStringMappingValues() {
    final Map<String, Object> config =
        Map.of(ITEMSPATH, PAYLOAD_ITEMS, HEADERMAPPING, Map.of("a", 1, "b", "payload.x"));

    StepVerifier.create(processor.prepare(config)).verifyComplete();
  }

  @Test
  void testExplicitNullItemsStrictAndNonStrict() {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Collections.singletonMap(ITEMS, null));

    StepVerifier.create(processor.process(Flux.just(parent), Map.of(ITEMSPATH, PAYLOAD_ITEMS)))
        .expectErrorMatches(e -> e.getMessage().contains("itemsPath null"))
        .verify();
    StepVerifier.create(
            processor.process(
                Flux.just(parent), Map.of(ITEMSPATH, PAYLOAD_ITEMS, STRICTMODE, false)))
        .verifyComplete();
  }

  private Flux<Message<?>> splitWith(final Map<String, Object> extra) {
    final Message<?> parent =
        DefaultMessage.create(UUID.randomUUID(), Map.of("items", Collections.singletonList(1)));
    final Map<String, Object> config = new java.util.HashMap<>(extra);
    config.putIfAbsent("itemsPath", "payload.items");
    return processor.process(Flux.just(parent), config);
  }

  @Test
  void testNullValuedMappingStrictFails() {
    StepVerifier.create(splitWith(Map.of("headerMapping", Map.of("h", "null"), "strictMode", true)))
        .expectErrorMatches(e -> e.getMessage().contains("Mapping null"))
        .verify();
  }

  @Test
  void testNullValuedMappingNonStrictSkipsHeader() {
    StepVerifier.create(
            splitWith(Map.of("headerMapping", Map.of("h", "null"), "strictMode", false)))
        .expectNextMatches(m -> !m.metadata().containsKey("h"))
        .verifyComplete();
  }

  @Test
  void testOutputPortsBlankAndNamedErrorPort() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of("errorPort", " ")));
    assertEquals(List.of("default", "e"), processor.getOutputPorts(Map.of("errorPort", "e")));
  }

  @Test
  void testPrepareWithNonMapMapping() {
    StepVerifier.create(processor.prepare(Map.of("itemsPath", "x", "headerMapping", "oops")))
        .verifyComplete();
  }

  @Test
  void testBlankErrorPortFallsBackToStrictFailure() {
    StepVerifier.create(splitWith(Map.of("itemsPath", "1/0", "errorPort", " ")))
        .expectError()
        .verify();
  }
}
