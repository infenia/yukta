// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.filter;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Checks a Map payload for required fields and field types, routing it to {@code default} when
 * valid or to {@code invalid} (with the failure reason set) otherwise.
 */
@Component
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.UseConcurrentHashMap"})
public final class ValidateProcessor implements ProcessorPlugin {

  /** Metrics reporter. */
  private final PluginMetricsReporter reporter;

  /** Creates the plugin without metrics. */
  public ValidateProcessor() {
    this.reporter = PluginMetricsReporter.NOOP;
  }

  /**
   * Spring constructor.
   *
   * @param reporterProvider optional metrics reporter
   */
  @Autowired
  public ValidateProcessor(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
  }

  /** Required. */
  private static final String REQUIRED = "required";

  /** Types. */
  private static final String TYPES = "types";

  /** Invalid. */
  private static final String INVALID = "invalid";

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Validates the payload's required fields and types; routes failures to the 'invalid' port.",
        "Configure with:\n"
            + "- required: list of field names that must be present and non-null.\n"
            + "- types: map of field name to string|number|boolean|object|array.\n"
            + "Valid messages leave on 'default'; invalid ones on 'invalid' with the reasons in"
            + " failureReason.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-blue-100 text-blue-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M8 12l3 3 5-6"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Validate</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                        <div class="text-[10px] text-slate-400 leading-none mt-1">default | invalid</div>
                    </div>
                </div>
                """,
                140,
                80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.optional(
            "required", ConfigField.Type.LIST, null, "Fields that must be present and non-null"),
        ConfigField.optional(
            "types", ConfigField.Type.MAP, null, "Field to string|number|boolean|object|array"));
  }

  @Override
  public String getType() {
    return "VALIDATE";
  }

  @Override
  public List<String> getOutputPorts() {
    return List.of("default", INVALID);
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          if (config.get(REQUIRED) == null && config.get(TYPES) == null) {
            throw new IllegalArgumentException("'required' or 'types' must be configured");
          }
          if (config.get(REQUIRED) != null && !(config.get(REQUIRED) instanceof Collection)) {
            throw new IllegalArgumentException("'required' must be a list of field names");
          }
          if (config.get(TYPES) != null && !(config.get(TYPES) instanceof Map)) {
            throw new IllegalArgumentException("'types' must be a map of field name to type");
          }
          types(config).values().forEach(ValidateProcessor::checkType);
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return PluginGuard.counted(doProcess(input, config), reporter, "validate");
  }

  private Flux<Message<?>> doProcess(
      final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.map(message -> route(message, config));
  }

  private static Message<?> route(final Message<?> message, final Map<String, Object> config) {
    final List<String> errors = new ArrayList<>();
    if (message.payload() instanceof Map<?, ?> payload) {
      if (config.get(REQUIRED) instanceof Collection<?> required) {
        required.stream()
            .filter(field -> payload.get(field) == null)
            .forEach(field -> errors.add("missing field: " + field));
      }
      types(config)
          .forEach(
              (field, type) -> {
                final Object value = payload.get(field);
                if (value != null && !matches(value, type)) {
                  errors.add("field " + field + " is not " + type);
                }
              });
    } else {
      errors.add("payload is not an object");
    }
    return errors.isEmpty()
        ? message.withSourcePort("default")
        : message.withFailure("VALIDATE", String.join("; ", errors), "").withSourcePort(INVALID);
  }

  private static Map<String, String> types(final Map<String, Object> config) {
    final Map<String, String> result = new java.util.LinkedHashMap<>();
    if (config.get(TYPES) instanceof Map<?, ?> declared) {
      declared.forEach((field, type) -> result.put(String.valueOf(field), String.valueOf(type)));
    }
    return result;
  }

  private static void checkType(final String type) {
    if (!List.of("string", "number", "boolean", "object", "array").contains(type)) {
      throw new IllegalArgumentException("unsupported type: " + type);
    }
  }

  private static boolean matches(final Object value, final String type) {
    return switch (type) {
      case "string" -> value instanceof CharSequence;
      case "number" -> value instanceof Number;
      case "boolean" -> value instanceof Boolean;
      case "object" -> value instanceof Map;
      default -> value instanceof Collection;
    };
  }
}
