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
package com.infenia.yukta.plugin.core.transformer;

import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.gateway.MessagingGateway;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.util.MapUtils;
import com.infenia.yukta.util.SpelUtils;
import com.infenia.yukta.util.VariableResolver;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Implementation of the Content Enricher pattern. Retrieves additional data from an external
 * resource and appends it to the message payload.
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.AvoidCatchingGenericException",
  "PMD.LawOfDemeter",
  "PMD.GodClass"
})
public class EnricherProcessor implements ProcessorPlugin {

  private static final String TYPE = "ENRICHER";

  private static final String CONF_SRC_TYPE = "sourceType";
  private static final String CONF_RES_REF = "resourceRef";
  private static final String CONF_LOOKUP_KEY = "lookupKey";
  private static final String CONF_TARGET_PATH = "targetPath";
  private static final String CONF_MAPPING = "mapping";
  private static final String CONF_ERR_POLICY = "errorPolicy";
  private static final String CONF_ERR_PORT = "errorPort";
  private static final String CONF_STRICT = "strictMode";

  private static final String SRC_ENV = "ENVIRONMENT";
  private static final String SRC_COMP = "COMPUTATION";
  private static final String SRC_EXT = "EXTERNAL";

  private static final String POL_FAIL = "FAIL";
  private static final String POL_IGNORE = "IGNORE";
  private static final String POL_ROUTE = "ROUTE";

  private static final String DEFAULT_PORT = "default";
  private static final String DEFAULT_ERR_PORT = "error";
  private static final String PAYLOAD_FIELD = "payload";

  private final VariableResolver variableResolver;
  private final ApplicationContext appContext;

  /**
   * Constructs a new EnricherProcessor.
   *
   * @param variableResolver the variable resolver for environment lookups
   * @param appContext the application context for resolving gateways
   */
  public EnricherProcessor(
      final VariableResolver variableResolver, final ApplicationContext appContext) {
    this.variableResolver = variableResolver;
    this.appContext = appContext;
  }

  @Override
  public String getType() {
    return TYPE;
  }

  @Override
  public String getDescription() {
    return "Retrieves additional data from an external resource and appends to the payload.";
  }

  @Override
  public String getUsagePattern() {
    return "Configure with:\n"
        + "- sourceType: 'ENVIRONMENT', 'COMPUTATION', or 'EXTERNAL' (default).\n"
        + "- resourceRef: The ID of the MessagingGateway (Required for EXTERNAL).\n"
        + "- lookupKey: SpEL for lookup key. Defaults to #payload or #payload.id.\n"
        + "- targetPath: Dot-notation path for injection. Defaults to 'payload'.\n"
        + "- mapping: Map of targetField -> sourceSpEL.\n"
        + "- errorPolicy: 'FAIL' (default), 'IGNORE', or 'ROUTE'.\n"
        + "- errorPort: Port for ROUTE policy. Defaults to 'error'.\n"
        + "- strictMode: Boolean. If true (default), fails if no data is returned.";
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
                d="M12 9v3m0 0v3m0-3h3m-3 0H9m12 0a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span class="ml-2 font-heading text-xs font-bold text-slate-700 \
              uppercase">Enricher</span>
            </div>
            """,
            140,
            80));
  }

  @Override
  public List<String> getOutputPorts(final Map<String, Object> config) {
    final String errorPolicy = (String) config.getOrDefault(CONF_ERR_POLICY, POL_FAIL);
    if (POL_ROUTE.equalsIgnoreCase(errorPolicy)) {
      final String errorPort = (String) config.getOrDefault(CONF_ERR_PORT, DEFAULT_ERR_PORT);
      return List.of(DEFAULT_PORT, errorPort);
    }
    return List.of(DEFAULT_PORT);
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    final String lookupKey = (String) config.get(CONF_LOOKUP_KEY);
    SpelUtils.preParse(lookupKey);

    @SuppressWarnings("unchecked")
    final Map<String, String> mapping = (Map<String, String>) config.get(CONF_MAPPING);
    if (mapping != null) {
      mapping.values().forEach(SpelUtils::preParse);
    }
    return Mono.empty();
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String sourceType = (String) config.getOrDefault(CONF_SRC_TYPE, SRC_EXT);
    final String resourceRef = (String) config.get(CONF_RES_REF);
    final String targetPath = (String) config.getOrDefault(CONF_TARGET_PATH, PAYLOAD_FIELD);
    final String errorPolicy = (String) config.getOrDefault(CONF_ERR_POLICY, POL_FAIL);
    final String errorPort = (String) config.getOrDefault(CONF_ERR_PORT, DEFAULT_ERR_PORT);
    final boolean strictMode = (Boolean) config.getOrDefault(CONF_STRICT, true);

    return input.flatMap(
        message ->
            Mono.deferContextual(
                ctx -> {
                  final String nodeId = ctx.getOrDefault("nodeId", "unknown");
                  return enrich(message, sourceType, resourceRef, config)
                      .flatMap(
                          result ->
                              applyResult(message, nodeId, result, targetPath, config, strictMode))
                      .switchIfEmpty(
                          Mono.defer(
                              () ->
                                  applyResult(
                                      message, nodeId, null, targetPath, config, strictMode)))
                      .onErrorResume(
                          e -> handleEnrichmentError(message, nodeId, e, errorPolicy, errorPort));
                }));
  }

  private Mono<Object> enrich(
      final Message<?> message,
      final String sourceType,
      final String resourceRef,
      final Map<String, Object> config) {

    final String lookupKeyExpr = (String) config.get(CONF_LOOKUP_KEY);
    final Mono<Object> lookupKeyMono = resolveLookupKey(message, lookupKeyExpr);

    return switch (sourceType.toUpperCase(Locale.ROOT)) {
      case SRC_ENV -> lookupKeyMono.flatMap(key -> variableResolver.resolve("${" + key + "}"));
      case SRC_COMP -> lookupKeyMono;
      case SRC_EXT -> lookupKeyMono.flatMap(key -> invokeGateway(message, resourceRef, key));
      default -> Mono.error(new IllegalArgumentException("Unsupported sourceType: " + sourceType));
    };
  }

  private Mono<Object> resolveLookupKey(final Message<?> message, final String expression) {
    if (expression == null || expression.isBlank()) {
      final Object payload = message.getPayload();
      if (payload instanceof String || payload instanceof Number || payload instanceof Boolean) {
        return Mono.just(payload);
      }
      return SpelUtils.evaluate(
          "#payload.id",
          message,
          Map.of(PAYLOAD_FIELD, payload != null ? payload : Collections.emptyMap()));
    }
    return SpelUtils.evaluate(
        expression,
        message,
        Map.of(
            PAYLOAD_FIELD,
            message.getPayload() != null ? message.getPayload() : Collections.emptyMap(),
            "metadata",
            message.getMetadata()));
  }

  private Mono<Object> invokeGateway(
      final Message<?> message, final String resourceRef, final Object lookupKey) {
    if (resourceRef == null || resourceRef.isBlank()) {
      return Mono.error(
          new IllegalArgumentException("resourceRef is mandatory for EXTERNAL source"));
    }

    try {
      final MessagingGateway gateway = appContext.getBean(resourceRef, MessagingGateway.class);
      final Message<Object> request = message.withPayload(lookupKey);
      return gateway.sendAndReceive(request).map(Message::getPayload);
    } catch (final Exception e) {
      return Mono.error(
          new WorkflowExecutionException("Failed to invoke gateway: " + resourceRef, e));
    }
  }

  private Mono<Message<?>> applyResult(
      final Message<?> message,
      final String nodeId,
      final Object result,
      final String targetPath,
      final Map<String, Object> config,
      final boolean strictMode) {

    if (result == null && strictMode) {
      return Mono.error(
          new WorkflowExecutionException("Enrichment returned no data in strict mode"));
    }

    if (result == null) {
      return Mono.just(message.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId));
    }

    @SuppressWarnings("unchecked")
    final Map<String, String> mapping = (Map<String, String>) config.get(CONF_MAPPING);
    final Object dataToInject = mapResult(result, mapping, message);

    if (PAYLOAD_FIELD.equalsIgnoreCase(targetPath)) {
      return Mono.just(
          message.withPayload(dataToInject).withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId));
    }

    final Map<String, Object> mutablePayload = MapUtils.asMutableMap(message.getPayload());
    String path = targetPath;
    if (path.startsWith("payload.")) {
      path = path.substring(8);
    }
    MapUtils.setNestedValue(mutablePayload, path, dataToInject);

    return Mono.just(
        message.withPayload(mutablePayload).withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId));
  }

  private Object mapResult(
      final Object result, final Map<String, String> mapping, final Message<?> message) {
    if (mapping == null || mapping.isEmpty()) {
      return result;
    }

    final Map<String, Object> mappedData = MapUtils.asMutableMap(Collections.emptyMap());
    final Map<String, Object> variables =
        Map.of(
            PAYLOAD_FIELD,
            message.getPayload() != null ? message.getPayload() : Map.of(),
            "metadata",
            message.getMetadata(),
            "result",
            result);

    mapping.forEach(
        (targetField, expression) -> {
          final Object value = SpelUtils.evaluateSync(expression, message, variables);
          MapUtils.setNestedValue(mappedData, targetField, value);
        });

    return mappedData;
  }

  private Mono<Message<?>> handleEnrichmentError(
      final Message<?> message,
      final String nodeId,
      final Throwable error,
      final String errorPolicy,
      final String errorPort) {

    if (log.isErrorEnabled()) {
      log.error("Enrichment failed for node {}: {}", nodeId, error.getMessage());
    }

    return switch (errorPolicy.toUpperCase(Locale.ROOT)) {
      case POL_IGNORE -> Mono.just(message.withSourcePort(DEFAULT_PORT).withAddedHistory(nodeId));
      case POL_ROUTE ->
          Mono.just(
              message
                  .withSourcePort(errorPort != null ? errorPort : DEFAULT_ERR_PORT)
                  .withAddedHistory(nodeId)
                  .withHeader("error_message", error.getMessage()));
      case POL_FAIL ->
          Mono.error(
              error instanceof WorkflowExecutionException
                  ? error
                  : new WorkflowExecutionException("Enrichment failed", error));
      default ->
          Mono.error(new IllegalArgumentException("Unsupported errorPolicy: " + errorPolicy));
    };
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    final String sourceType = (String) config.getOrDefault(CONF_SRC_TYPE, SRC_EXT);
    if (SRC_EXT.equalsIgnoreCase(sourceType)) {
      final String resourceRef = (String) config.get(CONF_RES_REF);
      if (resourceRef == null || resourceRef.isBlank()) {
        return Mono.error(
            new IllegalArgumentException("resourceRef is mandatory for EXTERNAL source"));
      }
    }

    final String errorPolicy = (String) config.getOrDefault(CONF_ERR_POLICY, POL_FAIL);
    if (!POL_FAIL.equalsIgnoreCase(errorPolicy)
        && !POL_IGNORE.equalsIgnoreCase(errorPolicy)
        && !POL_ROUTE.equalsIgnoreCase(errorPolicy)) {
      return Mono.error(new IllegalArgumentException("Unsupported errorPolicy: " + errorPolicy));
    }

    return Mono.empty();
  }
}
