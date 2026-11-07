// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.SupervisorCommonSettings;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.supervisor.SettingsValidationException.Issue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Checks supervisor settings against a type's {@link ConfigField} schema and the common schema.
 * Messages never repeat a value, because it may be a secret. Keys match exactly.
 */
@UtilityClass
public class SupervisorSettingsValidator {

  /** The prefix of every field name in an issue. */
  public static final String PREFIX = "settings.";

  /**
   * Checks the settings.
   *
   * @param settings the settings
   * @param typeSchema the type's own fields
   * @throws SettingsValidationException listing every unknown key, missing required key and value
   *     of the wrong type
   */
  public static void check(final Map<String, Object> settings, final List<ConfigField> typeSchema) {
    final List<ConfigField> schema = new ArrayList<>(typeSchema);
    schema.addAll(SupervisorCommonSettings.SCHEMA);
    final List<Issue> issues = new ArrayList<>();
    final Set<String> known = new HashSet<>();
    schema.forEach(field -> known.add(field.name()));
    settings.keySet().stream()
        .filter(key -> !known.contains(key))
        .forEach(key -> issues.add(new Issue(PREFIX + key, "is not a setting of this type")));
    for (final ConfigField field : schema) {
      final Object value = settings.get(field.name());
      if (value == null) {
        if (field.required()) {
          issues.add(new Issue(PREFIX + field.name(), "is required"));
        }
      } else {
        final String problem = problem(field, value);
        if (problem != null) {
          issues.add(new Issue(PREFIX + field.name(), problem));
        }
      }
    }
    if (!issues.isEmpty()) {
      throw new SettingsValidationException(issues);
    }
  }

  private static String problem(final ConfigField field, final Object value) {
    return switch (field.type()) {
      case STRING, EXPRESSION -> problemUnless(plainText(value), "must be text");
      case SECRET -> problemUnless(secretText(value), "must be text");
      case NUMBER -> problemUnless(value instanceof Number, "must be a number");
      case BOOLEAN -> problemUnless(bool(value), "must be true or false");
      case MAP -> problemUnless(value instanceof Map, "must be an object");
      case ENUM -> choice(field, value);
      case LIST -> list(field, value);
    };
  }

  /** The message when the value is not allowed, or null when it is. */
  private static String problemUnless(final boolean allowed, final String message) {
    return allowed ? null : message;
  }

  /** A string or a number, which may stand for text. */
  private static boolean plainText(final Object value) {
    return value instanceof String || value instanceof Number;
  }

  /** A string or an object, which may hold a secret. */
  private static boolean secretText(final Object value) {
    return value instanceof String || value instanceof Map;
  }

  private static boolean bool(final Object value) {
    return value instanceof Boolean
        || value instanceof String text
            && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text));
  }

  private static String choice(final ConfigField field, final Object value) {
    if (value instanceof String text
        && (field.options().isEmpty()
            || field.options().stream()
                .anyMatch(option -> option.equalsIgnoreCase(text.strip())))) {
      return null;
    }
    return "must be one of " + field.options();
  }

  private static String list(final ConfigField field, final Object value) {
    if (!(value instanceof Collection<?> items)) {
      return "must be a list";
    }
    // list values are names such as commands, never secrets, so they may be quoted
    return field.options().isEmpty()
        ? null
        : items.stream()
            .filter(item -> !(item instanceof String && field.options().contains(item)))
            .findFirst()
            .map(item -> "holds '" + item + "', which is not one of " + field.options())
            .orElse(null);
  }
}
