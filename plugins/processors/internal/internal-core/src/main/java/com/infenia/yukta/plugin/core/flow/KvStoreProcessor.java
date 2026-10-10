// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared in-memory key-value state that survives across workflow runs (until restart). Operations:
 * get, put, incr, delete.
 */
@Component
@SuppressWarnings("PMD.UseConcurrentHashMap")
@NoArgsConstructor
public final class KvStoreProcessor implements ProcessorPlugin {

  /** Default cap on distinct keys. */
  private static final int MAX_ENTRIES = 10_000;

  /** Supported operations. */
  private static final List<String> OPS = List.of("get", "put", "incr", "delete");

  // ponytail: in-memory only (lost on restart, single node); back with a database when durability
  // or clustering matters.
  /** Values by namespace-qualified key. */
  private final Map<String, Object> store = new ConcurrentHashMap<>();

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Shared in-memory key-value store for state across workflow runs.",
        "Configure with:\n"
            + "- op: get | put | incr | delete (required).\n"
            + "- key: key to use; defaults to the payload's 'key' field.\n"
            + "- namespace: optional prefix isolating keys (default none).\n"
            + "- maxEntries: cap on distinct keys (default 10000); new keys beyond it fail.\n"
            + "- put stores payload.value; incr adds payload.value (default 1) to a number.\n"
            + "Emits {key, value} (value is null when absent).",
        Optional.empty());
  }

  @Override
  public String getType() {
    return "KV_STORE";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          if (!OPS.contains(String.valueOf(config.get("op")))) {
            throw new IllegalArgumentException("op must be one of " + OPS);
          }
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String operation = String.valueOf(config.get("op"));
    return input.map(
        message -> {
          final Map<?, ?> payload = message.payload() instanceof Map<?, ?> map ? map : Map.of();
          final Object key = config.getOrDefault("key", payload.get("key"));
          if (key == null) {
            throw new IllegalArgumentException("key is required (config or payload.key)");
          }
          final String full = config.getOrDefault("namespace", "") + ":" + key;
          final Object value =
              switch (operation) {
                case "put" -> {
                  final Object stored = payload.get("value");
                  if (stored == null) {
                    throw new IllegalArgumentException("put requires payload.value");
                  }
                  checkCapacity(full, config);
                  store.put(full, stored);
                  yield stored;
                }
                case "incr" -> {
                  final Number add = delta(payload);
                  checkCapacity(full, config);
                  yield store.merge(full, add, KvStoreProcessor::sum);
                }
                case "delete" -> store.remove(full);
                default -> store.get(full);
              };
          final Map<String, Object> out = new java.util.HashMap<>();
          out.put("key", key);
          out.put("value", value);
          return message.withPayload(out).withSourcePort("default");
        });
  }

  // ponytail: size check is not atomic with the insert, so the cap can be overshot slightly under
  // heavy concurrency.
  private void checkCapacity(final String fullKey, final Map<String, Object> config) {
    final int max = config.get("maxEntries") instanceof Number cap ? cap.intValue() : MAX_ENTRIES;
    if (!store.containsKey(fullKey) && store.size() >= max) {
      throw new IllegalStateException("KV store is full (" + max + " entries)");
    }
  }

  private static Number delta(final Map<?, ?> payload) {
    final Object value = payload.get("value");
    if (value != null && !isWhole(value)) {
      throw new IllegalArgumentException("incr requires a whole-number payload.value");
    }
    return value == null ? 1L : (Number) value;
  }

  private static Number sum(final Object old, final Object add) {
    if (!isWhole(old)) {
      throw new IllegalArgumentException("incr requires the stored value to be a whole number");
    }
    return ((Number) old).longValue() + ((Number) add).longValue();
  }

  private static boolean isWhole(final Object value) {
    return value instanceof Long || value instanceof Integer;
  }
}
