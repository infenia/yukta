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
package com.infenia.yukta.plugin.trigger;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.VariableResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Constant source plugin emits a message with predefined variables at workflow startup. */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"})
public class ConfigVariableSource implements TriggerPlugin {

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
  public Mono<Void> prepare(final Map<String, Object> config) {
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());
    log.atDebug().log(
        "Preparing constant source trigger: caching {} static variable(s)", variables.size());
    return Flux.fromIterable(variables.values())
        .filter(resolver::isStatic)
        .flatMap(
            val ->
                resolver
                    .resolve(val)
                    .doOnNext(
                        resolved -> {
                          staticValueCache.put(val, resolved);
                          log.atDebug().log("Cached static value");
                        }))
        .then()
        .doFinally(
            signalType -> log.atDebug().log("Constant source trigger preparation completed"));
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    @SuppressWarnings("unchecked")
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());
    final String target = (String) config.getOrDefault("target", TARGET_PAYLOAD);

    log.atDebug().log(
        "Starting constant source trigger: emitting {} variable(s) to {}",
        variables.size(),
        target);
    return resolveVariables(variables)
        .<Message<?>>map(
            resolvedVars -> {
              final Map<String, Object> metadata = new HashMap<>();
              Object resultPayload = new HashMap<>();

              if (TARGET_METADATA.equalsIgnoreCase(target)) {
                resolvedVars.forEach((k, v) -> MapUtils.setNestedValue(metadata, k, v));
                log.atDebug().log(
                    "Emitted message with variables in metadata: {} keys", resolvedVars.size());
              } else {
                final Map<String, Object> payloadMap = new HashMap<>();
                resolvedVars.forEach((k, v) -> MapUtils.setNestedValue(payloadMap, k, v));
                resultPayload = payloadMap;
                log.atDebug().log(
                    "Emitted message with variables in payload: {} keys", resolvedVars.size());
              }

              return DefaultMessage.create(UUID.randomUUID(), resultPayload).withMetadata(metadata);
            })
        .flux();
  }

  private Mono<Map<String, Object>> resolveVariables(final Map<String, Object> variables) {
    log.atDebug().log("Resolving {} variable(s)", variables.size());
    return Flux.fromIterable(variables.entrySet())
        .flatMap(
            entry -> {
              final Object val = entry.getValue();
              if (resolver.isStatic(val)) {
                final Object cached = staticValueCache.getOrDefault(val, val);
                log.atDebug().log("Variable '{}' resolved from cache", entry.getKey());
                return Mono.just(Map.entry(entry.getKey(), cached));
              }
              log.atDebug().log("Resolving variable '{}'", entry.getKey());
              return resolver.resolve(val).map(resolved -> Map.entry(entry.getKey(), resolved));
            })
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .doOnNext(
            resolved ->
                log.atDebug().log("All {} variable(s) resolved successfully", resolved.size()));
  }
}
