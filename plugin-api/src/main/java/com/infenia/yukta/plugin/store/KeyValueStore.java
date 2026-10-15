// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

import reactor.core.publisher.Mono;

/**
 * Key-value state that outlives a workflow run, behind {@code KV_STORE}. The default keeps it in
 * memory; a database backend makes it survive a restart and shareable between servers.
 */
public interface KeyValueStore {

  /**
   * Reads a value.
   *
   * @param key the full key
   * @return the value, or empty if there is none
   */
  Mono<Object> get(String key);

  /**
   * Stores a value, replacing an earlier one.
   *
   * @param key the full key
   * @param value the value (plain JSON values)
   * @param maxEntries the most distinct keys the store may hold
   * @return the stored value, or an {@link IllegalStateException} if the key is new and the store
   *     is full
   */
  Mono<Object> put(String key, Object value, int maxEntries);

  /**
   * Adds to a whole number, starting from zero.
   *
   * @param key the full key
   * @param delta what to add
   * @param maxEntries the most distinct keys the store may hold
   * @return the new value, or an {@link IllegalArgumentException} if the stored value is not a
   *     whole number, or an {@link IllegalStateException} if the key is new and the store is full
   */
  Mono<Number> incr(String key, long delta, int maxEntries);

  /**
   * Removes a value.
   *
   * @param key the full key
   * @return the removed value, or empty if there was none
   */
  Mono<Object> remove(String key);
}
