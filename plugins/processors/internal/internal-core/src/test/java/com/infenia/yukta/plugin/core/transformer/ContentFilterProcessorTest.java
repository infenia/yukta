// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Content filter processor test. */
@SuppressWarnings("PMD.TooManyMethods")
@NoArgsConstructor
class ContentFilterProcessorTest {

  /** Literal INCLUDE. */
  private static final String INCLUDE_2 = "INCLUDE";

  /** Literal Jane. */
  private static final String JANE = "Jane";

  /** Literal John. */
  private static final String JOHN = "John";

  /** Literal YUKTA. */
  private static final String YUKTA_2 = "YUKTA";

  /** Literal age. */
  private static final String AGE = "age";

  /** Literal appId. */
  private static final String APPID = "appId";

  /** Literal mode. */
  private static final String MODE = "mode";

  /** Literal name. */
  private static final String NAME = "name";

  /** Literal paths. */
  private static final String PATHS = "paths";

  /** Literal secret. */
  private static final String SECRET = "secret";

  /** Literal strictMode. */
  private static final String STRICTMODE = "strictMode";

  /** Literal system. */
  private static final String SYSTEM = "system";

  /** Literal user. */
  private static final String USER = "user";

  /** Literal user.name. */
  private static final String USER_NAME = "user.name";

  /** Processor. */
  private ContentFilterProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new ContentFilterProcessor();
  }

  @Test
  void testIncludePayload() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of(USER_NAME, "user.email"));
    final Map<String, Object> payload =
        Map.of(
            USER, Map.of(NAME, JOHN, "email", "john@example.com", AGE, 30),
            SYSTEM, Map.of("id", "123"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              final Map<String, Object> result = (Map<String, Object>) m.payload();
              assertEquals(1, result.size(), "Test include payload");
              final Map<String, Object> user = (Map<String, Object>) result.get(USER);
              assertEquals(2, user.size(), "Test include payload (2)");
              assertEquals(JOHN, user.get(NAME), "Test include payload (3)");
              assertEquals("john@example.com", user.get("email"), "Test include payload (4)");
              assertFalse(user.containsKey(AGE), "Test include payload (5)");
              assertFalse(result.containsKey(SYSTEM), "Test include payload (6)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testIncludeWithSpel() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of("#payload.user.name"));
    final Map<String, Object> payload = Map.of(USER, Map.of(NAME, JOHN, AGE, 30));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              final Map<String, Object> result = (Map<String, Object>) m.payload();
              final Map<String, Object> user = (Map<String, Object>) result.get(USER);
              assertEquals(JOHN, user.get(NAME), "Test include with spel");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testExcludePayload() {
    final Map<String, Object> config =
        Map.of(MODE, "EXCLUDE", PATHS, List.of("user.age", "system.id"));
    final Map<String, Object> payload =
        Map.of(
            USER, Map.of(NAME, JOHN, AGE, 30),
            SYSTEM, Map.of("id", "123", "version", "1.0"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              final Map<String, Object> result = (Map<String, Object>) m.payload();
              final Map<String, Object> user = (Map<String, Object>) result.get(USER);
              assertEquals(JOHN, user.get(NAME), "Test exclude payload");
              assertFalse(user.containsKey(AGE), "Test exclude payload (2)");
              final Map<String, Object> system = (Map<String, Object>) result.get(SYSTEM);
              assertEquals("1.0", system.get("version"), "Test exclude payload (3)");
              assertFalse(system.containsKey("id"), "Test exclude payload (4)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testIncludeMetadata() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of("metadata.appId", USER_NAME));
    final Map<String, Object> payload = Map.of(USER, Map.of(NAME, JOHN));
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), payload)
            .withMetadata(Map.of(APPID, YUKTA_2, SECRET, "PASS"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals(YUKTA_2, m.metadata().get(APPID), "Test include metadata");
              assertFalse(m.metadata().containsKey(SECRET), "Test include metadata (2)");
              assertTrue(
                  m.metadata().containsKey("traceId"),
                  "Test include metadata (3)"); // Technical header preserved
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testIncludeWithSpelMetadata() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of("#metadata.appId", USER_NAME));
    final Map<String, Object> payload = Map.of(USER, Map.of(NAME, JOHN));
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), payload)
            .withMetadata(Map.of(APPID, YUKTA_2, SECRET, "PASS"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals(YUKTA_2, m.metadata().get(APPID), "Test include with spel metadata");
              assertFalse(m.metadata().containsKey(SECRET), "Test include with spel metadata (2)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testFlatten() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of(USER_NAME, "user.address.city"), "flatten", true);
    final Map<String, Object> payload =
        Map.of(USER, Map.of(NAME, JOHN, "address", Map.of("city", "New York", "zip", "10001")));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              final Map<String, Object> result = (Map<String, Object>) m.payload();
              assertEquals(JOHN, result.get(USER_NAME), "Test flatten");
              assertEquals("New York", result.get("user.address.city"), "Test flatten (2)");
              assertEquals(2, result.size(), "Test flatten (3)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStrictModeMissingPath() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of("user.missing"), STRICTMODE, true);
    final Map<String, Object> payload = Map.of(USER, Map.of(NAME, JOHN));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testErrorPortRouting() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            INCLUDE_2,
            PATHS,
            List.of("user.missing"),
            STRICTMODE,
            true,
            "errorPort",
            "error-out");
    final Map<String, Object> payload = Map.of(USER, Map.of(NAME, JOHN));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("error-out", m.sourcePort(), "Test error port routing");
              assertEquals(
                  "Content Filter failed", m.failureReason(), "Test error port routing (2)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStreamingPayload() {
    final Map<String, Object> config = Map.of(MODE, INCLUDE_2, PATHS, List.of(NAME));
    final List<Map<String, Object>> payload =
        List.of(Map.of(NAME, JOHN, AGE, 30), Map.of(NAME, JANE, AGE, 25));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals(JOHN, ((Map) m.payload()).get(NAME), "Test streaming payload");
              assertFalse(((Map) m.payload()).containsKey(AGE), "Test streaming payload (2)");
              return true;
            })
        .expectNextMatches(
            m -> {
              assertEquals(JANE, ((Map) m.payload()).get(NAME), "Test streaming payload (3)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testFluxPayload() {
    final Map<String, Object> config = Map.of(MODE, INCLUDE_2, PATHS, List.of(NAME));
    final Flux<Map<String, Object>> payload =
        Flux.just(Map.of(NAME, JOHN, AGE, 30), Map.of(NAME, JANE, AGE, 25));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals(JOHN, ((Map) m.payload()).get(NAME), "Test flux payload");
              return true;
            })
        .expectNextMatches(
            m -> {
              assertEquals(JANE, ((Map) m.payload()).get(NAME), "Test flux payload (2)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testPrimitiveArrayPayload() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of("payload"), STRICTMODE, false);
    final int[] payload = {1, 2, 3};
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              return true;
            })
        .expectNextMatches(m -> true)
        .expectNextMatches(m -> true)
        .verifyComplete();
  }

  @Test
  void testNullPayload() {
    final Map<String, Object> config =
        Map.of(MODE, INCLUDE_2, PATHS, List.of("something"), STRICTMODE, false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), null);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("CONTENT-FILTER", processor.getType(), "Test type");
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.describe().uiDesign().isPresent(), "Test ui design");
    assertTrue(
        processor.describe().uiDesign().get().html().contains("C-Filter"), "Test ui design (2)");
  }

  @Test
  void testOutputPorts() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of()), "Test output ports");
    assertEquals(
        List.of("default", "err"),
        processor.getOutputPorts(Map.of("errorPort", "err")),
        "Test output ports (2)");
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of())).expectError().verify();
    StepVerifier.create(processor.validateConfig(Map.of(PATHS, "a"))).expectError().verify();
    StepVerifier.create(processor.validateConfig(Map.of(PATHS, List.of("a"), MODE, "X")))
        .expectError()
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of(PATHS, List.of("a")))).verifyComplete();
  }

  @Test
  void testPrepare() {
    StepVerifier.create(processor.prepare(Map.of(PATHS, List.of("#payload.a")))).verifyComplete();
  }

  private List<Message<?>> run(
      final String mode, final List<String> paths, final Object payload, final Object... extra) {
    return runMsg(mode, paths, DefaultMessage.create(UUID.randomUUID(), payload), extra);
  }

  private List<Message<?>> runMsg(
      final String mode, final List<String> paths, final Message<?> msg, final Object... extra) {
    final Map<String, Object> config = new java.util.HashMap<>();
    config.put("mode", mode);
    config.put("paths", paths);
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return processor.process(Flux.just(msg), config).collectList().block();
  }

  @Test
  void testStreamAndObjectArrayPayloads() {
    assertEquals(
        2,
        run("INCLUDE", List.of("a"), java.util.stream.Stream.of(Map.of("a", 1), Map.of("a", 2)))
            .size());
    assertEquals(
        2, run("INCLUDE", List.of("a"), new Object[] {Map.of("a", 1), Map.of("a", 2)}).size());
    assertEquals(3, run("INCLUDE", List.of("a"), new int[] {1, 2, 3}, "strictMode", false).size());
  }

  @Test
  void testIncludeNonMapPayload() {
    final List<Message<?>> out = run("INCLUDE", List.of("#payload", "metadata.x"), "hello");
    assertEquals("hello", out.get(0).payload());
    final List<Message<?>> plain = run("INCLUDE", List.of("whatever"), "hello");
    assertEquals("hello", plain.get(0).payload());
  }

  @Test
  void testIncludeMetadataCopiesCoreHeaders() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1))
            .withHeader("appId", "x")
            .withTraceId("t")
            .withReplyTo("r")
            .withSequence("s", 1, 2);
    final Map<String, Object> meta =
        runMsg("INCLUDE", List.of("headers.appId", "#headers.appId"), msg).get(0).metadata();
    assertEquals("x", meta.get("appId"));
    assertEquals("t", meta.get("traceId"));
    assertEquals("r", meta.get("replyTo"));
    assertEquals("s", meta.get("sequenceId"));
    assertEquals(1, meta.get("sequenceNumber"));
    assertEquals(2, meta.get("sequenceSize"));
    assertTrue(meta.containsKey("timestamp"));
    assertTrue(meta.containsKey("messageId"));
  }

  @Test
  void testIncludeMetadataOmitsAbsentCoreHeaders() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1)).withHeader("appId", "x");
    final Map<String, Object> meta =
        runMsg("INCLUDE", List.of("metadata.appId"), msg).get(0).metadata();
    assertFalse(meta.containsKey("replyTo"));
  }

  @Test
  void testExcludeAllPrefixes() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1, "b", 2, "c", 3))
            .withHeader("m1", 1)
            .withHeader("m2", 2)
            .withHeader("m3", 3)
            .withHeader("m4", 4)
            .withHeader("keep", 5);
    final Message<?> out =
        runMsg(
                "exclude",
                List.of(
                    "a", "#payload.b", "metadata.m1", "headers.m2", "#metadata.m3", "#headers.m4"),
                msg)
            .get(0);
    assertEquals(Map.of("c", 3), out.payload());
    assertEquals(Map.of("keep", 5), out.metadata());
  }

  @Test
  void testStrictMissingPathVariants() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", Map.of("b", 1), "s", "str"))
            .withHeader("m", 1);
    for (final String path :
        List.of(
            "zzz",
            "metadata.zzz",
            "headers.zzz",
            "#payload.zzz",
            "#payload.a.nope",
            "a.b.c",
            "#metadata.zzz",
            "#undefined")) {
      final Map<String, Object> cfg = Map.of("mode", "INCLUDE", "paths", List.of(path));
      StepVerifier.create(processor.process(Flux.just(msg), cfg))
          .expectError(WorkflowExecutionException.class)
          .verify();
    }
    assertEquals(
        1, runMsg("INCLUDE", List.of("headers.m", "metadata.m", "#payload.a", "a.b"), msg).size());
  }

  @Test
  void testStrictNonMapAndNullPayloads() {
    assertEquals(1, run("INCLUDE", List.of("x"), "str").size());
    final Map<String, Object> cfg = Map.of("mode", "INCLUDE", "paths", List.of("x"));
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), null)), cfg))
        .expectError(WorkflowExecutionException.class)
        .verify();
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), 5)),
                Map.of("mode", "INCLUDE", "paths", List.of("#payload"))))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testFailureHandlingGenericExceptionAndNonStrict() {
    final Map<String, Object> bad =
        Map.of("mode", "INCLUDE", "paths", List.of("#payload.a.nope()"), "strictMode", false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1));
    StepVerifier.create(processor.process(Flux.just(msg), bad))
        .expectNextMatches(m -> "default".equals(m.sourcePort()))
        .verifyComplete();

    final Map<String, Object> routed =
        Map.of(
            "mode",
            "INCLUDE",
            "paths",
            List.of("#payload.a.nope()"),
            "strictMode",
            false,
            "errorPort",
            "err");
    StepVerifier.create(processor.process(Flux.just(msg), routed))
        .expectNextMatches(m -> "err".equals(m.sourcePort()))
        .verifyComplete();

    final Map<String, Object> blankPort =
        Map.of("mode", "INCLUDE", "paths", List.of("#payload.a.nope()"), "errorPort", " ");
    StepVerifier.create(processor.process(Flux.just(msg), blankPort)).expectError().verify();
  }

  @Test
  void testFlattenOnlyAppliesToMapPayload() {
    assertEquals(
        "hello", run("INCLUDE", List.of("#payload"), "hello", "flatten", true).get(0).payload());
  }

  @Test
  void testPrepareExcludeModeAndValidateOkExclude() {
    StepVerifier.create(processor.prepare(Map.of("mode", "EXCLUDE", "paths", List.of("a"))))
        .verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "exclude", "paths", List.of("a"))))
        .verifyComplete();
  }

  @Test
  void testNullPayloadWithSpelPathAndBlankErrorPort() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), null).withHeader("m", 1);
    StepVerifier.create(
            processor.process(
                Flux.just(msg), Map.of("mode", "INCLUDE", "paths", List.of("#metadata.m"))))
        .expectNextCount(1)
        .verifyComplete();
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of("errorPort", " ")));
  }

  private List<Message<?>> run(
      final String mode, final List<String> paths, final Object payload, final Object... extra) {
    return runMsg(mode, paths, DefaultMessage.create(UUID.randomUUID(), payload), extra);
  }

  private List<Message<?>> runMsg(
      final String mode, final List<String> paths, final Message<?> msg, final Object... extra) {
    final Map<String, Object> config = new java.util.HashMap<>();
    config.put("mode", mode);
    config.put("paths", paths);
    for (int i = 0; i < extra.length; i += 2) {
      config.put((String) extra[i], extra[i + 1]);
    }
    return processor.process(Flux.just(msg), config).collectList().block();
  }

  @Test
  void testStreamAndObjectArrayPayloads() {
    assertEquals(
        2,
        run("INCLUDE", List.of("a"), java.util.stream.Stream.of(Map.of("a", 1), Map.of("a", 2)))
            .size());
    assertEquals(
        2, run("INCLUDE", List.of("a"), new Object[] {Map.of("a", 1), Map.of("a", 2)}).size());
    assertEquals(3, run("INCLUDE", List.of("a"), new int[] {1, 2, 3}, "strictMode", false).size());
  }

  @Test
  void testIncludeNonMapPayload() {
    final List<Message<?>> out = run("INCLUDE", List.of("#payload", "metadata.x"), "hello");
    assertEquals("hello", out.get(0).payload());
    final List<Message<?>> plain = run("INCLUDE", List.of("whatever"), "hello");
    assertEquals("hello", plain.get(0).payload());
  }

  @Test
  void testIncludeMetadataCopiesCoreHeaders() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1))
            .withHeader("appId", "x")
            .withTraceId("t")
            .withReplyTo("r")
            .withSequence("s", 1, 2);
    final Map<String, Object> meta =
        runMsg("INCLUDE", List.of("headers.appId", "#headers.appId"), msg).get(0).metadata();
    assertEquals("x", meta.get("appId"));
    assertEquals("t", meta.get("traceId"));
    assertEquals("r", meta.get("replyTo"));
    assertEquals("s", meta.get("sequenceId"));
    assertEquals(1, meta.get("sequenceNumber"));
    assertEquals(2, meta.get("sequenceSize"));
    assertTrue(meta.containsKey("timestamp"));
    assertTrue(meta.containsKey("messageId"));
  }

  @Test
  void testIncludeMetadataOmitsAbsentCoreHeaders() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1)).withHeader("appId", "x");
    final Map<String, Object> meta =
        runMsg("INCLUDE", List.of("metadata.appId"), msg).get(0).metadata();
    assertFalse(meta.containsKey("replyTo"));
  }

  @Test
  void testExcludeAllPrefixes() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1, "b", 2, "c", 3))
            .withHeader("m1", 1)
            .withHeader("m2", 2)
            .withHeader("m3", 3)
            .withHeader("m4", 4)
            .withHeader("keep", 5);
    final Message<?> out =
        runMsg(
                "exclude",
                List.of(
                    "a", "#payload.b", "metadata.m1", "headers.m2", "#metadata.m3", "#headers.m4"),
                msg)
            .get(0);
    assertEquals(Map.of("c", 3), out.payload());
    assertEquals(Map.of("keep", 5), out.metadata());
  }

  @Test
  void testStrictMissingPathVariants() {
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("a", Map.of("b", 1), "s", "str"))
            .withHeader("m", 1);
    for (final String path :
        List.of(
            "zzz",
            "metadata.zzz",
            "headers.zzz",
            "#payload.zzz",
            "#payload.a.nope",
            "a.b.c",
            "#metadata.zzz",
            "#undefined")) {
      final Map<String, Object> cfg = Map.of("mode", "INCLUDE", "paths", List.of(path));
      StepVerifier.create(processor.process(Flux.just(msg), cfg))
          .expectError(WorkflowExecutionException.class)
          .verify();
    }
    assertEquals(
        1, runMsg("INCLUDE", List.of("headers.m", "metadata.m", "#payload.a", "a.b"), msg).size());
  }

  @Test
  void testStrictNonMapAndNullPayloads() {
    assertEquals(1, run("INCLUDE", List.of("x"), "str").size());
    final Map<String, Object> cfg = Map.of("mode", "INCLUDE", "paths", List.of("x"));
    StepVerifier.create(
            processor.process(Flux.just(DefaultMessage.create(UUID.randomUUID(), null)), cfg))
        .expectError(WorkflowExecutionException.class)
        .verify();
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), 5)),
                Map.of("mode", "INCLUDE", "paths", List.of("#payload"))))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testFailureHandlingGenericExceptionAndNonStrict() {
    final Map<String, Object> bad =
        Map.of("mode", "INCLUDE", "paths", List.of("#payload.a.nope()"), "strictMode", false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("a", 1));
    StepVerifier.create(processor.process(Flux.just(msg), bad))
        .expectNextMatches(m -> "default".equals(m.sourcePort()))
        .verifyComplete();

    final Map<String, Object> routed =
        Map.of(
            "mode",
            "INCLUDE",
            "paths",
            List.of("#payload.a.nope()"),
            "strictMode",
            false,
            "errorPort",
            "err");
    StepVerifier.create(processor.process(Flux.just(msg), routed))
        .expectNextMatches(m -> "err".equals(m.sourcePort()))
        .verifyComplete();

    final Map<String, Object> blankPort =
        Map.of("mode", "INCLUDE", "paths", List.of("#payload.a.nope()"), "errorPort", " ");
    StepVerifier.create(processor.process(Flux.just(msg), blankPort)).expectError().verify();
  }

  @Test
  void testFlattenOnlyAppliesToMapPayload() {
    assertEquals(
        "hello", run("INCLUDE", List.of("#payload"), "hello", "flatten", true).get(0).payload());
  }

  @Test
  void testPrepareExcludeModeAndValidateOkExclude() {
    StepVerifier.create(processor.prepare(Map.of("mode", "EXCLUDE", "paths", List.of("a"))))
        .verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "exclude", "paths", List.of("a"))))
        .verifyComplete();
  }

  @Test
  void testNullPayloadWithSpelPathAndBlankErrorPort() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), null).withHeader("m", 1);
    StepVerifier.create(
            processor.process(
                Flux.just(msg), Map.of("mode", "INCLUDE", "paths", List.of("#metadata.m"))))
        .expectNextCount(1)
        .verifyComplete();
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of("errorPort", " ")));
  }
}
