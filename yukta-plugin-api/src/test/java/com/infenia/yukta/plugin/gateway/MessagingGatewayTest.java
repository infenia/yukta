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

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.MessageMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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

  private static class MockGateway
      extends AbstractMessagingGateway<String, String, String, String> {
    Message<?> lastRequest;

    MockGateway() {
      super(new StringMessageMapper(), new StringMessageMapper());
    }

    @Override
    protected <T1> Mono<Void> dispatch(Message<T1> message) {
      this.lastRequest = message;
      return Mono.empty();
    }

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
