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
package com.infenia.jagratha.plugin.core;

import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.util.MapUtils;
import com.infenia.jagratha.util.VariableResolver;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Constant processor enriches or replaces message payload/metadata with predefined variables. */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.AvoidDeeplyNestedIfStmts", "PMD.UseConcurrentHashMap"})
public class ConstantProcessor implements ProcessorPlugin {

  private static final String TYPE = "constant";
  private static final String MODE_ENRICH = "ENRICH";
  private static final String MODE_REPLACE = "REPLACE";
  private static final String TARGET_METADATA = "METADATA";
  private static final String TARGET_PAYLOAD = "PAYLOAD";
  private static final String POLICY_OVERWRITE = "OVERWRITE";
  private static final String POLICY_SKIP = "SKIP";
  private static final String POLICY_FAIL = "FAIL";

  private final VariableResolver resolver;
  private final Map<Object, Object> staticValueCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Enriches or replaces message payload or metadata with predefined constant variables.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- variables: Map of key-value pairs to inject. Supports SpEL expressions.\n"
        + "- mode: 'ENRICH' (adds to existing) or 'REPLACE' (replaces entire target). "
        + "Default is 'ENRICH'.\n"
        + "- target: 'PAYLOAD' or 'METADATA'. Default is 'PAYLOAD'.\n"
        + "- collisionPolicy: 'OVERWRITE', 'SKIP', or 'FAIL'. Default is 'OVERWRITE'.";
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
  public Flux<Message> process(final Flux<Message> input, final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault("mode", MODE_ENRICH);
    final String target = (String) config.getOrDefault("target", TARGET_PAYLOAD);
    final String collisionPolicy =
        (String) config.getOrDefault("collisionPolicy", POLICY_OVERWRITE);
    @SuppressWarnings("unchecked")
    final Map<String, Object> variables =
        (Map<String, Object>) config.getOrDefault("variables", Map.of());

    return input.flatMap(
        message ->
            resolveVariables(variables)
                .map(
                    resolvedVars -> {
                      final Map<String, Object> metadata = new HashMap<>(message.metadata());
                      Object payload = message.payload();

                      if (TARGET_METADATA.equalsIgnoreCase(target)) {
                        applyVariables(metadata, resolvedVars, mode, collisionPolicy);
                      } else {
                        final Map<String, Object> payloadMap =
                            MODE_REPLACE.equalsIgnoreCase(mode)
                                ? new HashMap<>()
                                : MapUtils.asMutableMap(payload);
                        applyVariables(payloadMap, resolvedVars, mode, collisionPolicy);
                        payload = payloadMap;
                      }

                      return new Message(
                          message.id(),
                          message.traceId(),
                          metadata,
                          payload,
                          message.timestamp(),
                          message.sourcePort(),
                          message.sourceNodeId());
                    }));
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

  private void applyVariables(
      final Map<String, Object> targetMap,
      final Map<String, Object> variables,
      final String mode,
      final String policy) {
    variables.forEach(
        (path, value) -> {
          if (MODE_ENRICH.equalsIgnoreCase(mode)) {
            final Object existing = MapUtils.getNestedValue(targetMap, path);
            if (existing != null) {
              if (POLICY_SKIP.equalsIgnoreCase(policy)) {
                return;
              }
              if (POLICY_FAIL.equalsIgnoreCase(policy)) {
                throw new IllegalStateException("Collision detected for key: " + path);
              }
            }
          }
          MapUtils.setNestedValue(targetMap, path, value);
        });
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    if (config.get("variables") == null) {
      return Mono.error(new IllegalArgumentException("variables is mandatory"));
    }
    return Mono.empty();
  }
}
