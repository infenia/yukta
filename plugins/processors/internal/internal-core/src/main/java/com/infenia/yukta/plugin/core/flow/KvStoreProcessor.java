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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared in-memory key-value state that survives across workflow runs (until restart). Operations:
 * get, put, incr, delete.
 */
@Component
public final class KvStoreProcessor implements ProcessorPlugin {

  /** Supported operations. */
  private static final List<String> OPS = List.of("get", "put", "incr", "delete");

  // ponytail: in-memory only (lost on restart, single node); back with a database when durability
  // or clustering matters.
  /** Values by namespace-qualified key. */
  private final Map<String, Object> store = new ConcurrentHashMap<>();

  /** Default constructor. */
  public KvStoreProcessor() {
    super();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Shared in-memory key-value store for state across workflow runs.",
        "Configure with:\n"
            + "- op: get | put | incr | delete (required).\n"
            + "- key: key to use; defaults to the payload's 'key' field.\n"
            + "- namespace: optional prefix isolating keys (default none).\n"
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
    final String op = String.valueOf(config.get("op"));
    return input.map(
        message -> {
          final Map<?, ?> payload = message.payload() instanceof Map<?, ?> map ? map : Map.of();
          final Object key = config.getOrDefault("key", payload.get("key"));
          if (key == null) {
            throw new IllegalArgumentException("key is required (config or payload.key)");
          }
          final String full = config.getOrDefault("namespace", "") + ":" + key;
          final Object value =
              switch (op) {
                case "put" -> {
                  final Object stored = payload.get("value");
                  if (stored == null) {
                    throw new IllegalArgumentException("put requires payload.value");
                  }
                  store.put(full, stored);
                  yield stored;
                }
                case "incr" ->
                    store.merge(
                        full, delta(payload), (old, add) -> sum((Number) old, (Number) add));
                case "delete" -> store.remove(full);
                default -> store.get(full);
              };
          final Map<String, Object> out = new java.util.HashMap<>();
          out.put("key", key);
          out.put("value", value);
          return message.withPayload(out).withSourcePort("default");
        });
  }

  private static Number delta(final Map<?, ?> payload) {
    return payload.get("value") instanceof Number number ? number : 1L;
  }

  private static Number sum(final Number left, final Number right) {
    return left.longValue() + right.longValue();
  }
}
