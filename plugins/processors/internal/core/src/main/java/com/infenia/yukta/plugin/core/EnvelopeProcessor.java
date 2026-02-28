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
import com.infenia.yukta.plugin.ProcessorPlugin;
import com.infenia.yukta.plugin.UiDesign;
import com.infenia.yukta.plugin.WorkflowExecutionException;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.SpelUtils;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implementation of the Envelope Wrapper / Unwrapper pattern. Wraps or unwraps message payloads
 * with technical headers and ensures compliance.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LawOfDemeter", "PMD.GodClass", "PMD.TooManyMethods"})
public class EnvelopeProcessor implements ProcessorPlugin {

  private static final String TYPE = "ENVELOPE";

  private static final String CONF_MODE = "mode";
  private static final String CONF_HEADERS = "headers";
  private static final String CONF_PROMOTE = "promote";
  private static final String CONF_ENVELOPE_KEY = "envelopeKey";
  private static final String CONF_STRICT = "strictMode";

  private static final String MODE_WRAP = "WRAP";
  private static final String MODE_UNWRAP = "UNWRAP";

  private static final String DEFAULT_PORT = "default";
  private static final String ERROR_PORT = "error";

  private static final String TRACE_ID_HEADER = "traceId";
  private static final String HISTORY_HEADER = "messageHistory";

  private static final String UNCHECKED = "unchecked";

  /** Default constructor. */
  public EnvelopeProcessor() {
    super();
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Wraps raw payloads into a Message envelope with technical metadata or unwraps them.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- mode: 'WRAP' (default) or 'UNWRAP'.\n"
        + "- headers: Map of key-value pairs (SpEL supported) to add during WRAP.\n"
        + "- promote: Map of targetHeaderName to sourceBodyPath for WRAP, or reversed for UNWRAP.\n"
        + "- envelopeKey: Key in the payload where the business data resides (for UNWRAP).\n"
        + "- strictMode: Boolean. If true (default), fails on missing promotion fields.";
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex items-center justify-center w-full h-full bg-slate-50 \
            border-2 border-slate-200 rounded-xl">
              <svg class="w-8 h-8 text-slate-500" fill="none" stroke="currentColor" \
              viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" \
                d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 \
            00-2 2v10a2 2 0 002 2z" />
              </svg>
              <span class="ml-2 font-heading text-xs font-bold text-slate-700 \
              uppercase">Envelope</span>
            </div>
            """,
            140,
            80));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    return List.of(DEFAULT_PORT, ERROR_PORT);
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    @SuppressWarnings(UNCHECKED)
    final Map<String, String> headers = (Map<String, String>) config.get(CONF_HEADERS);
    if (headers != null) {
      headers.values().forEach(SpelUtils::preParse);
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONF_MODE, MODE_WRAP);
    final boolean strictMode = (Boolean) config.getOrDefault(CONF_STRICT, true);

    return input.flatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  return executeMode(message, mode, config, strictMode)
                      .map(
                          m -> (Message<?>) m.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId))
                      .onErrorResume(
                          e ->
                              handleProcessingError(message, nodeId, e.getMessage())
                                  .cast(Message.class));
                }));
  }

  private Mono<Message<?>> handleProcessingError(
      final Message<?> message, final String nodeId, final String errorMessage) {
    log.error("Envelope processing failed for node {}: {}", nodeId, errorMessage);
    return Mono.just(
        message
            .withSourcePort(ERROR_PORT)
            .withAddedHistory(nodeId)
            .withHeader("failureReason", errorMessage));
  }

  @SuppressWarnings(UNCHECKED)
  private Mono<? extends Message<?>> executeMode(
      final Message<?> message,
      final String mode,
      final Map<String, Object> config,
      final boolean strictMode) {
    return switch (mode.toUpperCase(Locale.ROOT)) {
      case MODE_WRAP -> wrap((Message<Object>) message, config, strictMode);
      case MODE_UNWRAP -> unwrap(message, config, strictMode);
      default -> Mono.error(new IllegalArgumentException("Unsupported mode: " + mode));
    };
  }

  @SuppressWarnings(UNCHECKED)
  private <T> Mono<Message<T>> wrap(
      final Message<T> message, final Map<String, Object> config, final boolean strictMode) {
    return Mono.fromCallable(
        () -> {
          final Map<String, String> headConf = (Map<String, String>) config.get(CONF_HEADERS);
          final Map<String, String> promConf = (Map<String, String>) config.get(CONF_PROMOTE);

          final Message<T> wrapped = applyHeaders(message, headConf, strictMode);
          return applyPromotion(wrapped, promConf, strictMode);
        });
  }

  private <T> Message<T> applyHeaders(
      final Message<T> message, final Map<String, String> headersConfig, final boolean strict) {
    if (headersConfig == null) {
      return message;
    }
    final Map<String, Object> vars =
        Map.of(
            "payload",
            message.getPayload() != null ? message.getPayload() : Map.of(),
            "metadata",
            message.getMetadata());
    Message<T> wrappedMsg = message;
    for (final Map.Entry<String, String> entry : headersConfig.entrySet()) {
      final Object val = SpelUtils.evaluateSync(entry.getValue(), message, vars);
      if (val == null && strict) {
        throw new WorkflowExecutionException("Header evaluation failed for key: " + entry.getKey());
      }
      if (val != null) {
        wrappedMsg = wrappedMsg.withHeader(entry.getKey(), val);
      }
    }
    return wrappedMsg;
  }

  private <T> Message<T> applyPromotion(
      final Message<T> message, final Map<String, String> promoteConfig, final boolean strict) {
    if (promoteConfig == null) {
      return message;
    }
    final Map<String, Object> bodyMap = MapUtils.asMutableMap(message.getPayload());
    Message<T> wrappedMsg = message;
    for (final Map.Entry<String, String> entry : promoteConfig.entrySet()) {
      final Object value = MapUtils.getNestedValue(bodyMap, entry.getValue());
      if (value == null && strict) {
        throw new WorkflowExecutionException(
            "Promotion failed: field not found at path " + entry.getValue());
      }
      if (value != null) {
        wrappedMsg = wrappedMsg.withHeader(entry.getKey(), value);
      }
    }
    return wrappedMsg;
  }

  @SuppressWarnings(UNCHECKED)
  private Mono<Message<?>> unwrap(
      final Message<?> message, final Map<String, Object> config, final boolean strictMode) {
    return Mono.fromCallable(
        () -> {
          final String key = (String) config.get(CONF_ENVELOPE_KEY);
          final Map<String, String> headConf = (Map<String, String>) config.get(CONF_HEADERS);
          final Map<String, String> promConf = (Map<String, String>) config.get(CONF_PROMOTE);

          Object payload = message.getPayload();
          if (key != null && !key.isBlank()) {
            final Map<String, Object> map = MapUtils.asMutableMap(payload);
            payload = MapUtils.getNestedValue(map, key);
            if (payload == null && strictMode) {
              throw new WorkflowExecutionException(
                  "Unwrap failed: payload not found at key " + key);
            }
          }
          if (promConf != null && payload != null) {
            payload = restoreFields(message, payload, promConf, strictMode);
          }
          final Map<String, Object> metadata = purgeMetadata(message, headConf);
          return message.withPayload(payload).withMetadata(metadata);
        });
  }

  private Object restoreFields(
      final Message<?> msg,
      final Object payload,
      final Map<String, String> prom,
      final boolean strict) {
    final Map<String, Object> mutablePayload = MapUtils.asMutableMap(payload);
    for (final Map.Entry<String, String> entry : prom.entrySet()) {
      final Object value = msg.getMetadata().get(entry.getKey());
      if (value == null && strict) {
        throw new WorkflowExecutionException(
            "Un-promotion failed: metadata key not found: " + entry.getKey());
      }
      if (value != null) {
        MapUtils.setNestedValue(mutablePayload, entry.getValue(), value);
      }
    }
    return mutablePayload;
  }

  private Map<String, Object> purgeMetadata(
      final Message<?> message, final Map<String, String> headersConfig) {
    final Map<String, Object> newMetadata = new ConcurrentHashMap<>();
    final Map<String, Object> oldMetadata = message.getMetadata();

    if (oldMetadata.containsKey(TRACE_ID_HEADER)) {
      newMetadata.put(TRACE_ID_HEADER, oldMetadata.get(TRACE_ID_HEADER));
    }
    if (oldMetadata.containsKey(HISTORY_HEADER)) {
      newMetadata.put(HISTORY_HEADER, oldMetadata.get(HISTORY_HEADER));
    }

    if (headersConfig != null) {
      for (final Map.Entry<String, Object> entry : oldMetadata.entrySet()) {
        if (!headersConfig.containsKey(entry.getKey())) {
          newMetadata.put(entry.getKey(), entry.getValue());
        }
      }
      // Re-ensure safe-list
      if (oldMetadata.containsKey(TRACE_ID_HEADER)) {
        newMetadata.put(TRACE_ID_HEADER, oldMetadata.get(TRACE_ID_HEADER));
      }
      if (oldMetadata.containsKey(HISTORY_HEADER)) {
        newMetadata.put(HISTORY_HEADER, oldMetadata.get(HISTORY_HEADER));
      }
    }
    return newMetadata;
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String mode = (String) config.getOrDefault(CONF_MODE, MODE_WRAP);
    if (!MODE_WRAP.equalsIgnoreCase(mode) && !MODE_UNWRAP.equalsIgnoreCase(mode)) {
      return Mono.error(new IllegalArgumentException("Unsupported mode: " + mode));
    }
    return Mono.empty();
  }
}
