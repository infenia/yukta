// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
