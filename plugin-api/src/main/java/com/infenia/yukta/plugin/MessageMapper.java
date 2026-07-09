// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin;

import com.infenia.yukta.message.Message;

/**
 * Strategy for mapping between generic message envelopes and internal domain objects.
 *
 * @param <T> the message payload type
 * @param <D> the domain object type
 */
public interface MessageMapper<T, D> {

  /**
   * Map a message to a domain object.
   *
   * @param message the message to map
   * @return the domain object
   */
  D toDomain(Message<T> message);

  /**
   * Map a domain object back into a message envelope, potentially preserving headers.
   *
   * @param domain the domain object
   * @param original the original message to copy headers from
   * @return the new message
   */
  Message<T> fromDomain(D domain, Message<?> original);
}
