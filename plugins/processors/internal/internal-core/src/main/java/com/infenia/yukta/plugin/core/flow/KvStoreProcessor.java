// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.store.KeyValueStore;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared key-value state that survives across workflow runs: in memory (until restart) by default,
 * or in the application's {@link KeyValueStore} such as the PostgreSQL one. Operations: get, put,
 * incr, delete.
 */
@Slf4j
@Component
@SuppressWarnings("PMD.UseConcurrentHashMap")
public final class KvStoreProcessor implements ProcessorPlugin {

  /** Default cap on distinct keys. */
  private static final int MAX_ENTRIES = 10_000;

  /** Config and payload key naming the entry. */
  private static final String KEY = "key";

  /** Supported operations. */
  private static final List<String> OPS = List.of("get", "put", "incr", "delete");

  /** Where the values are kept: memory by default, a database when the application has one. */
  private final KeyValueStore store;

  /** Set once the in-memory warning has been logged. */
  private final AtomicBoolean warned = new AtomicBoolean();

  /** Keeps the values in memory. */
  public KvStoreProcessor() {
    this(new InMemoryKeyValueStore());
  }

  /**
   * Spring constructor.
   *
   * @param stores a database backed store, if the application has one
   */
  @Autowired
  public KvStoreProcessor(final ObjectProvider<KeyValueStore> stores) {
    this(stores.getIfAvailable(InMemoryKeyValueStore::new));
  }

  /* default */ KvStoreProcessor(final KeyValueStore store) {
    this.store = store;
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          if (store instanceof InMemoryKeyValueStore && warned.compareAndSet(false, true)) {
            log.atWarn()
                .log(
                    "KV_STORE keeps its values in memory: they are lost on restart and not shared"
                        + " between servers. Set yukta.kv.store-type=postgres to keep them.");
          }
        });
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Shared in-memory key-value store for state across workflow runs.",
        "Configure with:\n"
            + "- op: get | put | incr | delete (required).\n"
            + "- key: key to use; defaults to the payload's 'key' field.\n"
            + "- namespace: prefix isolating keys (default: the workflow id, so workflows do not"
            + " share keys; set it to the same value in two workflows to share).\n"
            + "- maxEntries: cap on distinct keys (default 10000); new keys beyond it fail.\n"
            + "- put stores payload.value; incr adds payload.value (default 1) to a number.\n"
            + "Emits {key, value} (value is null when absent).",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-blue-100 text-blue-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">KV Store</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                    </div>
                </div>
                """,
                140,
                80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice("op", true, null, "Operation", "get", "put", "incr", "delete"),
        ConfigField.optional(KEY, ConfigField.Type.STRING, null, "Key (default: payload key)"),
        ConfigField.optional(
            "namespace",
            ConfigField.Type.STRING,
            null,
            "Prefix isolating keys (default: workflow id)"),
        ConfigField.optional(
            "maxEntries",
            ConfigField.Type.NUMBER,
            MAX_ENTRIES,
            "Most distinct keys; new keys beyond it fail"));
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
    final int max = config.get("maxEntries") instanceof Number cap ? cap.intValue() : MAX_ENTRIES;
    return input.concatMap(
        message -> {
          final Map<?, ?> payload = message.payload() instanceof Map<?, ?> map ? map : Map.of();
          final Object key = config.getOrDefault(KEY, payload.get(KEY));
          if (key == null) {
            throw new IllegalArgumentException("key is required (config or payload.key)");
          }
          final String full = namespace(config, message) + ":" + key;
          return value(operation, full, payload, max)
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty())
              .map(
                  value -> {
                    final Map<String, Object> out = new java.util.HashMap<>();
                    out.put(KEY, key);
                    out.put("value", value.orElse(null));
                    return message.withPayload(out).withSourcePort("default");
                  });
        });
  }

  /** The configured namespace, else the workflow id so that two workflows do not share a key. */
  private static String namespace(final Map<String, Object> config, final Message<?> message) {
    final Object configured = config.get("namespace");
    return configured != null
        ? String.valueOf(configured)
        : Objects.toString(message.workflowId(), "");
  }

  private Mono<Object> value(
      final String operation, final String full, final Map<?, ?> payload, final int max) {
    return switch (operation) {
      case "put" -> {
        final Object stored = payload.get("value");
        if (stored == null) {
          throw new IllegalArgumentException("put requires payload.value");
        }
        yield store.put(full, stored, max);
      }
      case "incr" -> store.incr(full, delta(payload), max).map(number -> (Object) number);
      case "delete" -> store.remove(full);
      default -> store.get(full);
    };
  }

  private static long delta(final Map<?, ?> payload) {
    final Object value = payload.get("value");
    if (value != null && !(value instanceof Long || value instanceof Integer)) {
      throw new IllegalArgumentException("incr requires a whole-number payload.value");
    }
    return value == null ? 1L : ((Number) value).longValue();
  }
}
