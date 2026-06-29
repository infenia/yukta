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
package com.infenia.yukta.message.store;

import com.infenia.yukta.message.Message;
import reactor.core.publisher.Mono;

/**
 * Interface for a central repository of all messages that pass through the system. Used for
 * auditing, debugging, and Message History reconstruction.
 */
public interface MessageStore {

  /**
   * Asynchronously store a copy of a message.
   *
   * @param message the message to store
   * @return a Mono that completes when the message is accepted for storage
   */
  Mono<Void> store(Message<?> message);

  /**
   * Retrieve a message by its unique ID.
   *
   * @param messageId the message identifier
   * @return a Mono containing the message if found
   */
  Mono<Message<?>> get(String messageId);
}
