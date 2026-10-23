// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.plugin.store.KeyValueStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/** The default {@link KeyValueStore}: in memory, lost on restart, for one server. */
@SuppressWarnings("PMD.AvoidSynchronizedStatement")
public final class InMemoryKeyValueStore implements KeyValueStore {

  /** Values by namespace-qualified key. */
  private final Map<String, Object> store = new ConcurrentHashMap<>();

  /** Makes the capacity check and the insert one step; reads do not take it. */
  private final Object lock = new Object();

  /** Creates an empty store. */
  public InMemoryKeyValueStore() {
    super();
  }

  @Override
  public Mono<Object> get(final String key) {
    return Mono.justOrEmpty(store.get(key));
  }

  @Override
  public Mono<Object> put(final String key, final Object value, final int maxEntries) {
    return Mono.fromSupplier(
        () -> {
          synchronized (lock) {
            checkCapacity(key, maxEntries);
            store.put(key, value);
          }
          return value;
        });
  }

  @Override
  public Mono<Number> incr(final String key, final long delta, final int maxEntries) {
    return Mono.fromSupplier(
        () -> {
          synchronized (lock) {
            checkCapacity(key, maxEntries);
            return (Number) store.merge(key, delta, InMemoryKeyValueStore::sum);
          }
        });
  }

  @Override
  public Mono<Object> remove(final String key) {
    return Mono.justOrEmpty(store.remove(key));
  }

  private void checkCapacity(final String key, final int max) {
    if (!store.containsKey(key) && store.size() >= max) {
      throw new IllegalStateException("KV store is full (" + max + " entries)");
    }
  }

  private static Number sum(final Object old, final Object add) {
    if (!(old instanceof Long || old instanceof Integer)) {
      throw new IllegalArgumentException("incr requires the stored value to be a whole number");
    }
    return ((Number) old).longValue() + ((Number) add).longValue();
  }
}
