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
package com.infenia.yukta.plugin;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Mock implementation of {@link MessagingGateway} for testing. Allows manual response injection.
 */
public class MockMessagingGateway implements MessagingGateway {

  private final Queue<Message<?>> responses = new ConcurrentLinkedQueue<>();
  private final Queue<Message<?>> receivedMessages = new ConcurrentLinkedQueue<>();

  /** Default constructor. */
  public MockMessagingGateway() {
    super();
  }

  /**
   * Queue a response to be returned by {@link #sendAndReceive(Message)}.
   *
   * @param response the response message
   */
  public void queueResponse(final Message<?> response) {
    responses.add(response);
  }

  /**
   * Get all messages that have been sent through this gateway.
   *
   * @return queue of received messages
   */
  public Queue<Message<?>> getReceivedMessages() {
    return receivedMessages;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T, R> Mono<Message<R>> sendAndReceive(final Message<T> request) {
    receivedMessages.add(request);
    final Message<R> response = (Message<R>) responses.poll();
    if (response != null) {
      return Mono.just(response.withCorrelationId(request.getCorrelationId()));
    }
    return Mono.empty();
  }

  @Override
  public <T> Mono<Void> send(final Message<T> request) {
    receivedMessages.add(request);
    return Mono.empty();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <R> Flux<Message<R>> receive() {
    return Flux.fromIterable(responses).map(msg -> (Message<R>) msg);
  }
}
