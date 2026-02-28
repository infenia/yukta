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
