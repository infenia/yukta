// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.experimental.UtilityClass;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Reads {@link ModelPlaneSettings} from a settings map the way Spring reads them from properties,
 * so a profile accepts the same keys, spellings and defaults as {@code
 * yukta.control-plane.model.*}.
 */
@UtilityClass
public class ModelPlaneBinder {

  /**
   * Binds the settings.
   *
   * @param settings the settings, such as {@code {"name": "tev1:0.8b", "min-probability": 0.7}}
   * @return the settings with defaults filled in
   * @throws IllegalArgumentException if a value is of the wrong kind or the settings are invalid;
   *     the message names the key and never repeats a value, because it may be a secret
   */
  public static ModelPlaneSettings bind(final Map<String, Object> settings) {
    final Map<String, Object> flat = new ConcurrentHashMap<>();
    flatten("", settings, flat);
    try {
      return new Binder(new MapConfigurationPropertySource(flat))
          .bind(ConfigurationPropertyName.EMPTY, Bindable.of(ModelPlaneSettings.class))
          .orElseThrow(() -> new IllegalArgumentException("The model settings are empty"));
    } catch (final BindException e) {
      final var property = e.getProperty();
      final String key = property == null ? "" : property.getName().toString();
      // a bad value is reported by key only; an invalid combination by the settings' own message
      throw new IllegalArgumentException(
          ownMessage(e).orElse("Invalid model setting" + (key.isEmpty() ? "" : " '" + key + "'")),
          e);
    }
  }

  /** The message of an exception thrown by the settings themselves, which never holds a value. */
  private static Optional<String> ownMessage(final Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      // exactly IllegalArgumentException: NumberFormatException and the like quote the value
      if (cause.getClass() == IllegalArgumentException.class) {
        return Optional.ofNullable(cause.getMessage());
      }
    }
    return Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private static void flatten(
      final String prefix, final Map<String, Object> map, final Map<String, Object> out) {
    map.forEach(
        (key, value) -> {
          final String name = prefix.isEmpty() ? key : prefix + "." + key;
          if (value instanceof Map<?, ?> nested) {
            flatten(name, (Map<String, Object>) nested, out);
          } else if (value instanceof List<?> list) {
            final List<Object> items = new ArrayList<>(list);
            for (int i = 0; i < items.size(); i++) {
              out.put(name + "[" + i + "]", items.get(i));
            }
          } else if (value != null) {
            out.put(name, value);
          }
        });
  }
}
