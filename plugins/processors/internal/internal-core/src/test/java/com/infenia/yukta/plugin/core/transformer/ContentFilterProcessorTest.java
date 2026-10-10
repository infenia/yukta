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
}
