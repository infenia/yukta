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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.ClaimCheckStore;
import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.WorkflowExecutionException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ClaimCheckProcessorTest {

  private ClaimCheckProcessor processor;
  private ApplicationContext applicationContext;
  private ClaimCheckStore store;

  @BeforeEach
  void setUp() {
    applicationContext = mock(ApplicationContext.class);
    store = mock(ClaimCheckStore.class);
    processor = new ClaimCheckProcessor(applicationContext);
    when(applicationContext.getBean(eq("myStore"), eq(ClaimCheckStore.class))).thenReturn(store);
  }

  @Test
  void testCheckModeGenerated() {
    final Map<String, Object> config =
        Map.of(
            "mode", "CHECK",
            "storeRef", "myStore");
    final String largePayload = "very large data";
    final Message<String> msg = DefaultMessage.create(UUID.randomUUID(), largePayload);

    when(store.store(any(String.class), eq(largePayload))).thenReturn(Mono.just("generated-key"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "node-1")))
        .expectNextMatches(
            m -> {
              assertNull(m.getPayload());
              assertEquals("generated-key", m.getMetadata().get("yukta.claim_check"));
              assertTrue(m.getMessageHistory().contains("node-1"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testCheckModeMessageId() {
    final Map<String, Object> config =
        Map.of(
            "mode", "CHECK",
            "storeRef", "myStore",
            "strategy", "MESSAGE_ID");
    final String largePayload = "very large data";
    final Message<String> msg = DefaultMessage.create(UUID.randomUUID(), largePayload);
    final String messageId = msg.getMessageId();

    when(store.store(eq(messageId), eq(largePayload))).thenReturn(Mono.just(messageId));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals(messageId, m.getMetadata().get("yukta.claim_check"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testCheckModeBusinessKey() {
    final Map<String, Object> config =
        Map.of(
            "mode", "CHECK",
            "storeRef", "myStore",
            "strategy", "BUSINESS_KEY",
            "dataPath", "payload.orderId");
    final Map<String, Object> payload = Map.of("orderId", "ORD-123", "data", "large-content");
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    when(store.store(eq("ORD-123"), eq("ORD-123"))).thenReturn(Mono.just("ORD-123"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("ORD-123", m.getMetadata().get("yukta.claim_check"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testCheckModeNestedDataPath() {
    final Map<String, Object> config =
        Map.of(
            "mode", "CHECK",
            "storeRef", "myStore",
            "dataPath", "payload.nested.large");
    Map<String, Object> nested = new HashMap<>();
    nested.put("large", "huge-data");
    nested.put("small", "tiny-data");
    Map<String, Object> payload = Map.of("nested", nested);
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    when(store.store(any(String.class), eq("huge-data"))).thenReturn(Mono.just("key-1"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              Map<String, Object> p = (Map<String, Object>) m.getPayload();
              Map<String, Object> n = (Map<String, Object>) p.get("nested");
              assertNull(n.get("large"));
              assertEquals("tiny-data", n.get("small"));
              assertEquals("key-1", m.getMetadata().get("yukta.claim_check"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testRedeemMode() {
    final Map<String, Object> config =
        Map.of(
            "mode", "REDEEM",
            "storeRef", "myStore");
    final Message<Object> msg =
        DefaultMessage.create(UUID.randomUUID(), null).withHeader("yukta.claim_check", "key-123");

    when(store.retrieve("key-123")).thenReturn(Mono.just("restored-data"));
    when(store.remove("key-123")).thenReturn(Mono.empty());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("restored-data", m.getPayload());
              return true;
            })
        .verifyComplete();

    verify(store).remove("key-123");
  }

  @Test
  void testRedeemModeNoRemove() {
    final Map<String, Object> config =
        Map.of(
            "mode", "REDEEM",
            "storeRef", "myStore",
            "removeOnRedeem", false);
    final Message<Object> msg =
        DefaultMessage.create(UUID.randomUUID(), null).withHeader("yukta.claim_check", "key-123");

    when(store.retrieve("key-123")).thenReturn(Mono.just("restored-data"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("restored-data", m.getPayload());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testRedeemNotFoundStrictMode() {
    final Map<String, Object> config =
        Map.of(
            "mode", "REDEEM",
            "storeRef", "myStore",
            "strictMode", true);
    final Message<Object> msg =
        DefaultMessage.create(UUID.randomUUID(), null)
            .withHeader("yukta.claim_check", "missing-key");

    when(store.retrieve("missing-key")).thenReturn(Mono.empty());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testRedeemNotFoundWithDefaultPort() {
    final Map<String, Object> config =
        Map.of(
            "mode", "REDEEM",
            "storeRef", "myStore",
            "strictMode", false);
    final Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "original");
    final Message<String> msgWithHeader = msg.withHeader("yukta.claim_check", "missing-key");

    when(store.retrieve("missing-key")).thenReturn(Mono.empty());

    StepVerifier.create(processor.process(Flux.just(msgWithHeader), config))
        .expectNextMatches(
            m -> {
              assertEquals("original", m.getPayload());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testErrorPortRedeemFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "REDEEM",
            "storeRef", "myStore",
            "errorPort", "error-channel");
    final Message<Object> msg =
        DefaultMessage.create(UUID.randomUUID(), null).withHeader("yukta.claim_check", "fail-key");

    when(store.retrieve("fail-key")).thenReturn(Mono.error(new RuntimeException("Store down")));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("error-channel", m.getSourcePort());
              assertEquals("Store down", m.getMetadata().get("failure_reason"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testBusinessKeyMissingFails() {
    final Map<String, Object> config =
        Map.of(
            "mode", "CHECK",
            "storeRef", "myStore",
            "strategy", "BUSINESS_KEY",
            "dataPath", "payload.missing");
    final Message<Map<String, Object>> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("other", "data"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }
}
