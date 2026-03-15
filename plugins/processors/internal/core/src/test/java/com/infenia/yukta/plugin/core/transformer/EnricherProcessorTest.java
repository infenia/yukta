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
package com.infenia.yukta.plugin.core.transformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.gateway.MessagingGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.store.SecretProvider;
import com.infenia.yukta.util.VariableResolver;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class EnricherProcessorTest {

  private EnricherProcessor processor;
  private VariableResolver variableResolver;
  private ApplicationContext applicationContext;
  private SecretProvider secretProvider;

  @BeforeEach
  void setUp() {
    secretProvider = mock(SecretProvider.class);
    variableResolver = new VariableResolver(secretProvider);
    applicationContext = mock(ApplicationContext.class);
    processor = new EnricherProcessor(variableResolver, applicationContext);
  }

  @Test
  void testEnvironmentEnrichment() {
    System.setProperty("test.prop", "test-value");
    try {
      final Map<String, Object> config =
          Map.of(
              "sourceType", "ENVIRONMENT",
              "lookupKey", "'sys.test.prop'",
              "targetPath", "payload.enriched");
      final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("id", "123"));

      StepVerifier.create(
              processor
                  .process(Flux.just(msg), config)
                  .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
          .expectNextMatches(
              m -> {
                Map<String, Object> payload = (Map<String, Object>) m.getPayload();
                assertEquals("test-value", payload.get("enriched"));
                assertTrue(m.getMessageHistory().contains("test-node"));
                return true;
              })
          .verifyComplete();
    } finally {
      System.clearProperty("test.prop");
    }
  }

  @Test
  void testComputationEnrichment() {
    final Map<String, Object> config =
        Map.of(
            "sourceType", "COMPUTATION",
            "lookupKey", "payload.price * payload.quantity",
            "targetPath", "payload.total");
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("price", 10, "quantity", 5));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectNextMatches(
            m -> {
              Map<String, Object> payload = (Map<String, Object>) m.getPayload();
              assertEquals(50, payload.get("total"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testExternalEnrichment() {
    final MessagingGateway gateway = mock(MessagingGateway.class);
    when(applicationContext.getBean(eq("myGateway"), eq(MessagingGateway.class)))
        .thenReturn(gateway);
    when(gateway.sendAndReceive(any()))
        .thenReturn(Mono.just(DefaultMessage.create(UUID.randomUUID(), "external-data")));

    final Map<String, Object> config =
        Map.of(
            "sourceType", "EXTERNAL",
            "resourceRef", "myGateway",
            "lookupKey", "payload.id",
            "targetPath", "payload.ext");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("id", "123"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectNextMatches(
            m -> {
              Map<String, Object> payload = (Map<String, Object>) m.getPayload();
              assertEquals("external-data", payload.get("ext"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testEnrichmentWithMapping() {
    final Map<String, Object> config =
        Map.of(
            "sourceType", "COMPUTATION",
            "lookupKey", "payload.raw",
            "targetPath", "payload.data",
            "mapping", Map.of("val", "#result.value", "meta", "#metadata.m1"));
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("raw", Map.of("value", "v1")))
            .withMetadata(Map.of("m1", "mv1"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              Map<String, Object> payload = (Map<String, Object>) m.getPayload();
              Map<String, Object> data = (Map<String, Object>) payload.get("data");
              assertEquals("v1", data.get("val"));
              assertEquals("mv1", data.get("meta"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testErrorPolicyRoute() {
    final Map<String, Object> config =
        Map.of(
            "sourceType", "EXTERNAL",
            "resourceRef", "missingGateway",
            "errorPolicy", "ROUTE",
            "errorPort", "fail-port");
    when(applicationContext.getBean(eq("missingGateway"), eq(MessagingGateway.class)))
        .thenThrow(new RuntimeException("Gateway not found"));

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "trigger");

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectNextMatches(
            m -> {
              assertEquals("fail-port", m.getSourcePort());
              assertNotNull(m.getMetadata().get("error_message"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStrictModeFail() {
    final Map<String, Object> config =
        Map.of("sourceType", "COMPUTATION", "lookupKey", "null", "strictMode", true);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "trigger");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(e -> e instanceof WorkflowExecutionException)
        .verify();
  }

  @Test
  void testNonStrictModeIgnore() {
    final Map<String, Object> config =
        Map.of("sourceType", "COMPUTATION", "lookupKey", "null", "strictMode", false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "trigger");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "trigger".equals(m.getPayload()))
        .verifyComplete();
  }

  @Test
  void testDefaultLookupKey() {
    // For String payload, default lookupKey should be the payload itself
    final Map<String, Object> config =
        Map.of(
            "sourceType", "COMPUTATION",
            "targetPath", "payload");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "hello");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> m.getPayload().equals("hello"))
        .verifyComplete();

    // For Object payload, default lookupKey should be payload.id
    final Message<?> msgObj =
        DefaultMessage.create(UUID.randomUUID(), Map.of("id", "456", "other", "val"));
    StepVerifier.create(processor.process(Flux.just(msgObj), config))
        .expectNextMatches(m -> m.getPayload().equals("456"))
        .verifyComplete();
  }

  @Test
  void testMetadata() {
    assertEquals("ENRICHER", processor.getType());
    assertNotNull(processor.getDescription());
    assertNotNull(processor.getUsagePattern());
    assertTrue(processor.getUiDesign().isPresent());
  }

  @Test
  void testGetOutputPortsDefault() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of()));
  }

  @Test
  void testGetOutputPortsRoute() {
    assertEquals(
        List.of("default", "custom-err"),
        processor.getOutputPorts(Map.of("errorPolicy", "ROUTE", "errorPort", "custom-err")));
  }

  @Test
  void testUnsupportedSourceType() {
    final Map<String, Object> config = Map.of("sourceType", "INVALID");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "test");
    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(
            e ->
                e instanceof WorkflowExecutionException
                    && e.getCause() instanceof IllegalArgumentException
                    && e.getCause().getMessage().contains("sourceType"))
        .verify();
  }

  @Test
  void testIgnoreErrorPolicy() {
    // VariableResolver blocks access to keys containing PASSWORD
    final Map<String, Object> config =
        Map.of("sourceType", "ENVIRONMENT", "lookupKey", "'sys.PASSWORD'", "errorPolicy", "IGNORE");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "test");
    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "test".equals(m.getPayload()))
        .verifyComplete();
  }

  @Test
  void testInvalidErrorPolicy() {
    final Map<String, Object> config = Map.of("errorPolicy", "INVALID");
    StepVerifier.create(processor.validateConfig(config))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testResolveLookupKeyNullPayload() {
    final Map<String, Object> config = Map.of("sourceType", "COMPUTATION", "lookupKey", "'val'");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), null);
    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "val".equals(m.getPayload()))
        .verifyComplete();
  }

  @Test
  void testApplyResultWithNestedPayloadPath() {
    final Map<String, Object> config =
        Map.of(
            "sourceType", "COMPUTATION",
            "lookupKey", "10",
            "targetPath", "payload.nested.val");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("other", "x"));
    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              Map payload = (Map) m.getPayload();
              Map nested = (Map) payload.get("nested");
              return Integer.valueOf(10).equals(nested.get("val"));
            })
        .verifyComplete();
  }

  @Test
  void testInitializeWithMapping() {
    final Map<String, Object> config = Map.of("lookupKey", "key", "mapping", Map.of("f1", "exp1"));
    StepVerifier.create(processor.initialize(config)).verifyComplete();
  }
}
