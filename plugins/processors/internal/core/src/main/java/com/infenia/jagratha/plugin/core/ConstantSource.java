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
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.TriggerPlugin;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Constant source plugin emits a message with predefined variables at workflow startup. */
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"})
public class ConstantSource implements TriggerPlugin {

  private static final String TYPE = "CONSTANT_SOURCE";
  private static final String TARGET_METADATA = "METADATA";
  private static final String TARGET_PAYLOAD = "PAYLOAD";

  private final VariableResolver resolver;
  private final Map<Object, Object> staticValueCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Emits a message with predefined variables at workflow startup.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- variables: Map of key-value pairs to emit. Supports SpEL expressions.\n"
        + "- target: 'PAYLOAD' or 'METADATA'. Default is 'PAYLOAD'.";
  }

  @Override
  @SuppressWarnings("unchecked")
  public Mono<Void> initialize(final Map<String, Object> config) {
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());
    return Flux.fromIterable(variables.values())
        .filter(resolver::isStatic)
        .flatMap(
            val -> resolver.resolve(val).doOnNext(resolved -> staticValueCache.put(val, resolved)))
        .then();
  }

  @Override
  public Flux<Message> start(final Map<String, Object> config) {
    @SuppressWarnings("unchecked")
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());
    final String target = (String) config.getOrDefault("target", TARGET_PAYLOAD);

    return resolveVariables(variables)
        .map(
            resolvedVars -> {
              final Map<String, Object> metadata = new HashMap<>();
              Object resultPayload = new HashMap<>();

              if (TARGET_METADATA.equalsIgnoreCase(target)) {
                resolvedVars.forEach((k, v) -> MapUtils.setNestedValue(metadata, k, v));
              } else {
                final Map<String, Object> payloadMap = new HashMap<>();
                resolvedVars.forEach((k, v) -> MapUtils.setNestedValue(payloadMap, k, v));
                resultPayload = payloadMap;
              }

              return new Message(
                  UUID.randomUUID(),
                  UUID.randomUUID(), // New trace ID for a new trigger execution
                  metadata,
                  resultPayload,
                  java.time.Instant.now(),
                  null,
                  null);
            })
        .flux();
  }

  private Mono<Map<String, Object>> resolveVariables(final Map<String, Object> variables) {
    return Flux.fromIterable(variables.entrySet())
        .flatMap(
            entry -> {
              final Object val = entry.getValue();
              if (resolver.isStatic(val)) {
                final Object cached = staticValueCache.getOrDefault(val, val);
                return Mono.just(Map.entry(entry.getKey(), cached));
              }
              return resolver.resolve(val).map(resolved -> Map.entry(entry.getKey(), resolved));
            })
        .collectMap(Map.Entry::getKey, Map.Entry::getValue);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get("variables") == null) {
      return Mono.error(new IllegalArgumentException("variables is mandatory"));
    }
    return Mono.empty();
  }
}
