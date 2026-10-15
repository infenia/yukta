// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.plugin.util.MapUtils;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
@SuppressWarnings({"PMD.UseConcurrentHashMap"})
public class ConfigVariableSource implements TriggerPlugin {

  /** Type. */
  private static final String TYPE = "CONSTANT_SOURCE";

  /** Target metadata. */
  private static final String TARGET_METADATA = "METADATA";

  /** Target payload. */
  private static final String TARGET_PAYLOAD = "PAYLOAD";

  /** Canvas card. */
  private static final String CARD =
      """
      <div class="flex items-center w-full h-full bg-green-50/50 border-2 border-green-100 rounded-xl px-4 gap-3">
          <div class="flex-shrink-0 w-8 h-8 bg-green-100 rounded-lg flex items-center justify-center text-green-500">
              <span class="material-symbols-outlined text-xl">touch_app</span>
          </div>
          <div class="flex flex-col min-w-0">
              <div class="text-xs text-green-600 font-bold uppercase tracking-wider leading-none mb-1">Constant Source</div>
              <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
          </div>
      </div>
      """;

  /** Resolver. */
  private final VariableResolver resolver;

  /** Static value cache. */
  private final Map<Object, Object> staticValueCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Emits a message with predefined variables at workflow startup.",
        usagePattern(),
        Optional.of(new UiDesign(CARD, 140, 80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.optional(
            "variables",
            ConfigField.Type.MAP,
            Map.of(),
            "Key to value; dots nest; empty emits one empty message"),
        ConfigField.choice(
            "target", false, "PAYLOAD", "Where the variables go", "PAYLOAD", "METADATA"));
  }

  @Override
  public String getType() {
    return TYPE;
  }

  // A bare "return """ text block collides with this project's checkstyle
  // TextBlockGoogleStyleFormatting rule, which google-java-format's own formatting of `return`
  // statements can never satisfy (spotlessApply keeps re-collapsing it); string concatenation
  // stays.
  @SuppressWarnings("StringConcatToTextBlock")
  private static String usagePattern() {
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

              return DefaultMessage.create(UUID.randomUUID(), resultPayload)
                  .withMetadata(metadata)
                  .withSourcePort("default");
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
