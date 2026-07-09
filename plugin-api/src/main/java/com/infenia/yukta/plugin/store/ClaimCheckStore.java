// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

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
