// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.filter;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Checks a Map payload for required fields and field types, routing it to {@code default} when
 * valid or to {@code invalid} (with the failure reason set) otherwise.
 */
@Component
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.UseConcurrentHashMap"})
@NoArgsConstructor
public final class ValidateProcessor implements ProcessorPlugin {

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
        Optional.empty());
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
          types(config).values().forEach(ValidateProcessor::checkType);
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
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
