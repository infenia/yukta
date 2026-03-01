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

import com.infenia.yukta.plugin.message.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A Messaging Gateway acts as a semantic bridge between business logic and external systems.
 *
 * <p>It hides the complexity of reactive infrastructure (WebClient, Kafka, etc.) behind type-safe
 * domain-specific methods.
 */
public interface MessagingGateway {

  /**
   * Execute a request-reply interaction through the gateway.
   *
   * @param <T> the request payload type
   * @param <R> the response payload type
   * @param request the request message
   * @return a Mono of the response message
   */
  <T, R> Mono<Message<R>> sendAndReceive(Message<T> request);

  /**
   * Send a message through the gateway without expecting a direct reply.
   *
   * @param <T> the request payload type
   * @param request the request message
   * @return a Mono that completes when the message is sent
   */
  <T> Mono<Void> send(Message<T> request);

  /**
   * Receive a stream of messages from the gateway.
   *
   * @param <R> the response payload type
   * @return a Flux of incoming messages
   */
  <R> Flux<Message<R>> receive();
}
