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
package com.infenia.yukta.plugin.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.MessageMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class MessagingGatewayTest {

  @Test
  void testAbstractMessagingGatewayCorrelation() {
    final MockGateway gateway = new MockGateway();
    final Message<String> request = DefaultMessage.create(UUID.randomUUID(), "ping");

    final Mono<Message<String>> responseMono = gateway.sendAndReceive(request);

    // Simulate async response
    final String correlationId = gateway.lastRequest.getCorrelationId();
    assertNotNull(correlationId);

    final Message<String> response =
        DefaultMessage.create(UUID.randomUUID(), "pong").withCorrelationId(correlationId);

    gateway.handleResponse(response);

    StepVerifier.create(responseMono)
        .expectNextMatches(
            msg -> "pong".equals(msg.getPayload()) && correlationId.equals(msg.getCorrelationId()))
        .verifyComplete();
  }

  @Test
  void testAbstractMessagingGatewayExchange() {
    final StringMessageMapper mapper = new StringMessageMapper();
    final MockGateway gateway = new MockGateway();
    final Message<String> original = DefaultMessage.create(UUID.randomUUID(), "context");

    final Mono<String> responseMono = gateway.exchange("ping", original);

    final String correlationId = gateway.lastRequest.getCorrelationId();
    final Message<String> response =
        DefaultMessage.create(UUID.randomUUID(), "pong").withCorrelationId(correlationId);

    gateway.handleResponse(response);

    StepVerifier.create(responseMono).expectNext("pong").verifyComplete();
  }

  @Test
  void testAbstractMessagingGatewaySend() {
    final MockGateway gateway = new MockGateway();
    final Message<String> request = DefaultMessage.create(UUID.randomUUID(), "fire");

    StepVerifier.create(gateway.send(request)).verifyComplete();

    assertNotNull(gateway.lastRequest);
    assertEquals("fire", gateway.lastRequest.getPayload());
  }

  @Test
  void testAbstractMessagingGatewayErrorMapping() {
    final MockGateway gateway = new MockGateway();
    gateway.shouldFail = true;
    final Message<String> request = DefaultMessage.create(UUID.randomUUID(), "fail");

    StepVerifier.create(gateway.sendAndReceive(request))
        .expectError(com.infenia.yukta.plugin.exception.WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testAbstractMessagingGatewayResponseMissingCorrelation() {
    final MockGateway gateway = new MockGateway();
    final Message<String> response = DefaultMessage.create(UUID.randomUUID(), "pong");
    // correlationId is null
    gateway.handleResponse(response);
    // Should just log and return
  }

  @Test
  void testAbstractMessagingGatewayResponseUnknownCorrelation() {
    final MockGateway gateway = new MockGateway();
    final Message<String> response =
        DefaultMessage.create(UUID.randomUUID(), "pong").withCorrelationId("unknown");
    gateway.handleResponse(response);
    // Should just log and return
  }

  @Test
  void testAbstractMessagingGatewayShutdown() {
    final MockGateway gateway = new MockGateway();
    gateway.shutdown();
  }

  @Test
  void testAbstractMessagingGatewayTimeoutMapping() {
    final MockGateway gateway = new MockGateway();
    // Default timeout is 60s
    StepVerifier.withVirtualTime(
            () -> gateway.sendAndReceive(DefaultMessage.create(UUID.randomUUID(), "timeout")))
        .thenAwait(java.time.Duration.ofSeconds(61))
        .expectErrorMatches(
            e ->
                e instanceof com.infenia.yukta.plugin.exception.WorkflowExecutionException
                    && e.getMessage().contains("timed out"))
        .verify();
  }

  @Test
  void testAbstractMessagingGatewayWorkflowExecutionExceptionMapping() {
    final MockGateway gateway = new MockGateway();
    gateway.shouldFailWithWorkflowException = true;
    StepVerifier.create(gateway.send(DefaultMessage.create(UUID.randomUUID(), "fail")))
        .expectErrorMatches(
            e ->
                e instanceof com.infenia.yukta.plugin.exception.WorkflowExecutionException
                    && "explicit fail".equals(e.getMessage()))
        .verify();

    StepVerifier.create(gateway.sendAndReceive(DefaultMessage.create(UUID.randomUUID(), "fail")))
        .expectErrorMatches(
            e ->
                e instanceof com.infenia.yukta.plugin.exception.WorkflowExecutionException
                    && "explicit fail".equals(e.getMessage()))
        .verify();
  }

  @Test
  void testAbstractMessagingGatewaySendErrorMappingToWorkflowExecutionException() {
    final MockGateway gateway = new MockGateway();
    gateway.shouldFail = true;
    StepVerifier.create(gateway.send(DefaultMessage.create(UUID.randomUUID(), "fail")))
        .expectErrorMatches(
            e ->
                e instanceof com.infenia.yukta.plugin.exception.WorkflowExecutionException
                    && e.getMessage().contains("Failed to send message"))
        .verify();
  }

  @Test
  void testAbstractMessagingGatewaySendAndReceiveWithExplicitCorrelationId() {
    final MockGateway gateway = new MockGateway();
    final Message<String> request =
        DefaultMessage.create(UUID.randomUUID(), "ping").withCorrelationId("explicit-123");

    final Mono<Message<String>> responseMono = gateway.sendAndReceive(request);
    assertEquals("explicit-123", gateway.lastRequest.getCorrelationId());

    final Message<String> response =
        DefaultMessage.create(UUID.randomUUID(), "pong").withCorrelationId("explicit-123");
    gateway.handleResponse(response);

    StepVerifier.create(responseMono).expectNextCount(1).verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testAbstractMessagingGatewayPurgeExpired() throws Exception {
    final MockGateway gateway = new MockGateway();

    // Inject a pending reply that is already expired
    java.lang.reflect.Field field =
        AbstractMessagingGateway.class.getDeclaredField("pendingReplies");
    field.setAccessible(true);
    java.util.Map<String, Object> pendingReplies =
        (java.util.Map<String, Object>) field.get(gateway);

    Sinks.One<Message<String>> sink = Sinks.one();
    // createdAt = long ago
    long longAgo = System.currentTimeMillis() - 100000;

    // We need to use reflection to create PendingReply because it's private record
    Class<?> recordClass =
        Class.forName("com.infenia.yukta.plugin.gateway.AbstractMessagingGateway$PendingReply");
    java.lang.reflect.Constructor<?> constructor = recordClass.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    Object pendingReply = constructor.newInstance(sink, longAgo);

    pendingReplies.put("expired-key", pendingReply);

    // Call purgeExpiredReplies via reflection
    java.lang.reflect.Method method =
        AbstractMessagingGateway.class.getDeclaredMethod("purgeExpiredReplies");
    method.setAccessible(true);
    method.invoke(gateway);

    // Verify it was removed and error emitted
    assertTrue(pendingReplies.isEmpty());
    StepVerifier.create(sink.asMono())
        .expectErrorMatches(
            e ->
                e instanceof com.infenia.yukta.plugin.exception.WorkflowExecutionException
                    && e.getMessage().contains("expired and was purged"))
        .verify();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testAbstractMessagingGatewayPurgeMixed() throws Exception {
    final MockGateway gateway = new MockGateway();

    java.lang.reflect.Field field =
        AbstractMessagingGateway.class.getDeclaredField("pendingReplies");
    field.setAccessible(true);
    java.util.Map<String, Object> pendingReplies =
        (java.util.Map<String, Object>) field.get(gateway);

    Class<?> recordClass =
        Class.forName("com.infenia.yukta.plugin.gateway.AbstractMessagingGateway$PendingReply");
    java.lang.reflect.Constructor<?> constructor = recordClass.getDeclaredConstructors()[0];
    constructor.setAccessible(true);

    // 1. Expired
    Sinks.One<Message<String>> sink1 = Sinks.one();
    Object expired = constructor.newInstance(sink1, System.currentTimeMillis() - 100000);
    pendingReplies.put("expired", expired);

    // 2. Not expired
    Sinks.One<Message<String>> sink2 = Sinks.one();
    Object active = constructor.newInstance(sink2, System.currentTimeMillis());
    pendingReplies.put("active", active);

    java.lang.reflect.Method method =
        AbstractMessagingGateway.class.getDeclaredMethod("purgeExpiredReplies");
    method.setAccessible(true);
    method.invoke(gateway);

    // Verify
    assertEquals(1, pendingReplies.size());
    assertTrue(pendingReplies.containsKey("active"));
  }

  @Test
  void testAbstractMessagingGatewayShutdownTimeout() throws Exception {
    final MockGateway gateway = new MockGateway();

    // Use reflection to get the private scheduler and submit a long task
    java.lang.reflect.Field field = AbstractMessagingGateway.class.getDeclaredField("scheduler");
    field.setAccessible(true);
    java.util.concurrent.ScheduledExecutorService scheduler =
        (java.util.concurrent.ScheduledExecutorService) field.get(gateway);

    scheduler.submit(
        () -> {
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {
            // expected
          }
        });

    gateway.shutdown();
    // This should hit the awaitTermination returning false and calling shutdownNow
  }

  @Test
  void testAbstractMessagingGatewayShutdownInterrupted() throws Exception {
    final MockGateway gateway = new MockGateway();
    Thread.currentThread().interrupt();
    try {
      gateway.shutdown();
    } finally {
      // Clear interrupt flag
      Thread.interrupted();
    }
    // This should hit the catch block
  }

  private static class MockGateway
      extends AbstractMessagingGateway<String, String, String, String> {
    Message<?> lastRequest;
    boolean shouldFail;

    MockGateway() {
      super(new StringMessageMapper(), new StringMessageMapper());
    }

    @Override
    protected <T1> Mono<Void> dispatch(Message<T1> message) {
      if (shouldFailWithWorkflowException) {
        return Mono.error(
            new com.infenia.yukta.plugin.exception.WorkflowExecutionException("explicit fail"));
      }
      if (shouldFail) {
        return Mono.error(new RuntimeException("dispatch failed"));
      }
      this.lastRequest = message;
      return Mono.empty();
    }

    boolean shouldFailWithWorkflowException;

    @Override
    public <R1> Flux<Message<R1>> receive() {
      return Flux.empty();
    }

    @Override
    public void handleResponse(Message<String> response) {
      super.handleResponse(response);
    }
  }

  private static class StringMessageMapper implements MessageMapper<String, String> {
    @Override
    public String toDomain(Message<String> message) {
      return message.getPayload();
    }

    @Override
    public Message<String> fromDomain(String domain, Message<?> original) {
      return DefaultMessage.from(original, domain);
    }
  }
}
