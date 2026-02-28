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

import com.infenia.yukta.plugin.ClaimCheckStore;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.UiDesign;
import com.infenia.yukta.plugin.WorkflowExecutionException;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.SpelUtils;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implementation of the Claim Check pattern. Reduces message volume by storing parts of the payload
 * in a persistent store and replacing them with a claim check key.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods", "PMD.LongVariable"})
public class ClaimCheckProcessor implements ProcessorPlugin {

  private static final String TYPE = "claim-check";

  private static final String CONF_MODE = "mode";
  private static final String CONF_STORE_REF = "storeRef";
  private static final String CONF_KEY_PATH = "keyPath";
  private static final String CONF_DATA_PATH = "dataPath";
  private static final String CONF_STRATEGY = "strategy";
  private static final String CONF_REMOVE_ON_REDEEM = "removeOnRedeem";
  private static final String CONF_STRICT = "strictMode";
  private static final String CONF_ERROR_PORT = "errorPort";

  private static final String MODE_CHECK = "CHECK";
  private static final String MODE_REDEEM = "REDEEM";

  private static final String STRAT_GENERATED = "GENERATED";
  private static final String STRAT_MESSAGE_ID = "MESSAGE_ID";
  private static final String STRAT_BUSINESS_KEY = "BUSINESS_KEY";

  private static final String DEFAULT_KEY_PATH = "yukta.claim_check";
  private static final String DEFAULT_DATA_PATH = "payload";
  private static final String DEFAULT_PORT = "default";
  private static final String DEFAULT_ERR_PORT = "error";

  private final ApplicationContext appContext;

  /**
   * Constructs a new ClaimCheckProcessor.
   *
   * @param appContext the application context for resolving stores
   */
  public ClaimCheckProcessor(final ApplicationContext appContext) {
    this.appContext = appContext;
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Offloads large payloads to a store and replaces them with a claim check key (CHECK), "
        + "or retrieves the payload using a key (REDEEM).";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'CHECK' (default) or 'REDEEM'.\n"
        + "- storeRef: Bean ID of the ClaimCheckStore implementation (Required).\n"
        + "- keyPath: Metadata key for the claim check. Defaults to 'yukta.claim_check'.\n"
        + "- dataPath: Payload path to offload/restore. Defaults to 'payload'.\n"
        + "- strategy: 'GENERATED' (default), 'MESSAGE_ID', or 'BUSINESS_KEY'.\n"
        + "- removeOnRedeem: Boolean. If true (default), deletes from store after REDEEM.\n"
        + "- strictMode: Boolean. If true (default), fails on missing keys or store errors.\n"
        + "- errorPort: Optional port to route failures.";
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex items-center justify-center w-full h-full rounded-xl border-2 \
            {{(config.mode || 'CHECK') == 'CHECK' ? 'bg-indigo-50 border-indigo-200' : 'bg-emerald-50 border-emerald-200'}}">
              <svg class="w-8 h-8 {{(config.mode || 'CHECK') == 'CHECK' ? 'text-indigo-500' : 'text-emerald-500'}}" \
              fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" \
                d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 \
                1.994 0 013 12V7a4 4 0 014-4z" />
              </svg>
              <div class="flex flex-col ml-2">
                <span class="text-[10px] font-bold text-slate-400">CLAIM CHECK</span>
                <span class="font-heading text-xs font-bold text-slate-700 \
                uppercase">{{config.mode || 'CHECK'}}</span>
              </div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final String errorPort = (String) config.get(CONF_ERROR_PORT);
    if (errorPort != null && !errorPort.isBlank()) {
      return List.of(DEFAULT_PORT, errorPort);
    }
    return List.of(DEFAULT_PORT);
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String strategy = (String) config.getOrDefault(CONF_STRATEGY, STRAT_GENERATED);
    if (STRAT_BUSINESS_KEY.equalsIgnoreCase(strategy)) {
      final String dataPath = (String) config.getOrDefault(CONF_DATA_PATH, DEFAULT_DATA_PATH);
      SpelUtils.preParse(dataPath);
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONF_MODE, MODE_CHECK);
    final String storeRef = (String) config.get(CONF_STORE_REF);
    final String keyPath = (String) config.getOrDefault(CONF_KEY_PATH, DEFAULT_KEY_PATH);
    final String dataPath = (String) config.getOrDefault(CONF_DATA_PATH, DEFAULT_DATA_PATH);
    final String errorPort = (String) config.get(CONF_ERROR_PORT);
    final boolean strictMode = (Boolean) config.getOrDefault(CONF_STRICT, true);

    return input.flatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  final ClaimCheckStore store = appContext.getBean(storeRef, ClaimCheckStore.class);

                  if (MODE_CHECK.equalsIgnoreCase(mode)) {
                    return handleCheck(message, nodeId, store, keyPath, dataPath, config)
                        .onErrorResume(
                            e -> handleFailure(message, nodeId, e, errorPort, strictMode));
                  } else {
                    final boolean removeOnRedeem =
                        (Boolean) config.getOrDefault(CONF_REMOVE_ON_REDEEM, true);
                    return handleRedeem(
                            message, nodeId, store, keyPath, dataPath, removeOnRedeem, strictMode)
                        .onErrorResume(
                            e -> handleFailure(message, nodeId, e, errorPort, strictMode));
                  }
                }));
  }

  private Mono<Message<?>> handleCheck(
      final Message<?> message,
      final String nodeId,
      final ClaimCheckStore store,
      final String keyPath,
      final String dataPath,
      final Map<String, Object> config) {

    return resolveKey(message, config)
        .flatMap(
            key -> {
              final Object dataToStore = resolveData(message, dataPath);
              return store
                  .store(key, dataToStore)
                  .map(
                      storedKey -> {
                        final Message<?> modified =
                            removeData(message, dataPath).withHeader(keyPath, storedKey);
                        return modified.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId);
                      });
            });
  }

  private Mono<Message<?>> handleRedeem(
      final Message<?> message,
      final String nodeId,
      final ClaimCheckStore store,
      final String keyPath,
      final String dataPath,
      final boolean removeOnRedeem,
      final boolean strictMode) {

    final String key = (String) message.getMetadata().get(keyPath);
    if (key == null || key.isBlank()) {
      return Mono.error(new WorkflowExecutionException("Claim check key not found at: " + keyPath));
    }

    return store
        .retrieve(key)
        .flatMap(
            data -> {
              if (data == null && strictMode) {
                return Mono.error(
                    new WorkflowExecutionException("Claim check data not found for key: " + key));
              }
              final Message<?> modified = restoreData(message, dataPath, data);
              if (removeOnRedeem) {
                return store
                    .remove(key)
                    .then(
                        Mono.just(modified.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId)));
              }
              return Mono.just(modified.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId));
            })
        .switchIfEmpty(
            Mono.defer(
                () -> {
                  if (strictMode) {
                    return Mono.error(
                        new WorkflowExecutionException(
                            "Claim check data not found for key: " + key));
                  }
                  return Mono.just(message.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId));
                }));
  }

  private Mono<String> resolveKey(final Message<?> message, final Map<String, Object> config) {
    final String strategy = (String) config.getOrDefault(CONF_STRATEGY, STRAT_GENERATED);
    return switch (strategy.toUpperCase(Locale.ROOT)) {
      case STRAT_GENERATED -> Mono.just(UUID.randomUUID().toString());
      case STRAT_MESSAGE_ID -> Mono.just(message.getMessageId());
      case STRAT_BUSINESS_KEY -> {
        final String dataPath = (String) config.getOrDefault(CONF_DATA_PATH, DEFAULT_DATA_PATH);
        yield SpelUtils.evaluate(dataPath, message, Map.of("payload", message.getPayload()))
            .map(Object::toString)
            .switchIfEmpty(
                Mono.error(
                    new WorkflowExecutionException(
                        "Business key evaluation returned null for path: " + dataPath)));
      }
      default -> Mono.error(new IllegalArgumentException("Unsupported strategy: " + strategy));
    };
  }

  private Object resolveData(final Message<?> message, final String dataPath) {
    if (DEFAULT_DATA_PATH.equalsIgnoreCase(dataPath)) {
      return message.getPayload();
    }
    final Map<String, Object> payloadMap = MapUtils.asMutableMap(message.getPayload());
    String path = dataPath;
    if (path.startsWith("payload.")) {
      path = path.substring(8);
    }
    return MapUtils.getNestedValue(payloadMap, path);
  }

  private Message<?> removeData(final Message<?> message, final String dataPath) {
    if (DEFAULT_DATA_PATH.equalsIgnoreCase(dataPath)) {
      return message.withPayload(null);
    }
    final Map<String, Object> payloadMap = MapUtils.asMutableMap(message.getPayload());
    String path = dataPath;
    if (path.startsWith("payload.")) {
      path = path.substring(8);
    }
    MapUtils.removeNestedValue(payloadMap, path);
    return message.withPayload(payloadMap);
  }

  private Message<?> restoreData(
      final Message<?> message, final String dataPath, final Object data) {
    if (DEFAULT_DATA_PATH.equalsIgnoreCase(dataPath)) {
      return message.withPayload(data);
    }
    final Map<String, Object> payloadMap = MapUtils.asMutableMap(message.getPayload());
    String path = dataPath;
    if (path.startsWith("payload.")) {
      path = path.substring(8);
    }
    MapUtils.setNestedValue(payloadMap, path, data);
    return message.withPayload(payloadMap);
  }

  private Mono<Message<?>> handleFailure(
      final Message<?> message,
      final String nodeId,
      final Throwable error,
      final String errorPort,
      final boolean strictMode) {

    if (log.isErrorEnabled()) {
      log.error("Claim check operation failed for node {}: {}", nodeId, error.getMessage());
    }

    if (errorPort != null && !errorPort.isBlank()) {
      return Mono.just(
          message
              .withSourcePort(errorPort)
              .withAddedHistory(nodeId)
              .withHeader("failure_reason", error.getMessage()));
    }

    if (strictMode) {
      return Mono.error(
          error instanceof WorkflowExecutionException
              ? error
              : new WorkflowExecutionException("Claim check operation failed", error));
    }

    return Mono.just(message.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId));
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String storeRef = (String) config.get(CONF_STORE_REF);
    if (storeRef == null || storeRef.isBlank()) {
      return Mono.error(new IllegalArgumentException("storeRef is mandatory"));
    }
    return Mono.empty();
  }
}
