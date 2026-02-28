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

import reactor.core.publisher.Mono;

/** Interface for storing large payloads outside the message envelope. */
public interface ClaimCheckStore {

  /**
   * Store a large payload and return a claim check key.
   *
   * @param payload the payload to store
   * @return a Mono containing the claim check key
   */
  Mono<String> store(Object payload);

  /**
   * Store a large payload with a specific key.
   *
   * @param key the claim check key to use
   * @param payload the payload to store
   * @return a Mono containing the claim check key
   */
  Mono<String> store(String key, Object payload);

  /**
   * Retrieve a payload using its claim check key.
   *
   * @param key the claim check key
   * @return a Mono containing the retrieved payload
   */
  Mono<Object> retrieve(String key);

  /**
   * Remove a payload from the store.
   *
   * @param key the claim check key
   * @return a Mono that completes when removal is done
   */
  Mono<Void> remove(String key);
}
