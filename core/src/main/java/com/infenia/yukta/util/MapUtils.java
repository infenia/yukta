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
package com.infenia.yukta.util;

import java.util.HashMap;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.core.convert.support.DefaultConversionService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Utility methods for working with Maps and nested structures. */
@UtilityClass
@SuppressWarnings({
  "PMD.OnlyOneReturn",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
public class MapUtils {

  /** ObjectMapper for converting objects to/from Maps. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Conversion service for type casting. */
  private static final DefaultConversionService CONV_SERVICE = new DefaultConversionService();

  /**
   * Set a value in a nested map using dot notation.
   *
   * @param map the map to modify
   * @param path the dot-separated path (e.g. "a.b.c")
   * @param value the value to set
   */
  public static void setNestedValue(
      final Map<String, Object> map, final String path, final Object value) {
    final String[] parts = path.split("\\.");
    Map<String, Object> current = map;
    for (int i = 0; i < parts.length - 1; i++) {
      final String part = parts[i];
      final Object next = current.get(part);
      if (next instanceof Map) {
        final Map<String, Object> mutableNext = new HashMap<>((Map<String, Object>) next);
        current.put(part, mutableNext);
        current = mutableNext;
      } else {
        final Map<String, Object> newMap = new HashMap<>();
        current.put(part, newMap);
        current = newMap;
      }
    }
    current.put(parts[parts.length - 1], value);
  }

  /**
   * Convert an object to a mutable Map.
   *
   * @param payload the object to convert
   * @return a mutable Map representation
   */
  public static Map<String, Object> asMutableMap(final Object payload) {
    if (payload instanceof Map) {
      return new HashMap<>((Map<String, Object>) payload);
    }
    if (payload == null) {
      return new HashMap<>();
    }
    return MAPPER.convertValue(payload, new TypeReference<>() {});
  }

  /**
   * Convert a value to a specific type.
   *
   * @param value the value to convert
   * @param targetType the target class
   * @param <T> the target type
   * @return the converted value
   */
  public static <T> T convert(final Object value, final Class<T> targetType) {
    if (value == null) {
      return null;
    }
    if (targetType.isInstance(value)) {
      return (T) value;
    }
    return CONV_SERVICE.convert(value, targetType);
  }

  /**
   * Get a value from a nested map using dot notation.
   *
   * @param map the map to search
   * @param path the dot-separated path (e.g. "a.b.c")
   * @return the value, or null if not found
   */
  public static Object getNestedValue(final Map<String, Object> map, final String path) {
    final String[] parts = path.split("\\.");
    Object current = map;
    for (final String part : parts) {
      if (!(current instanceof Map)) {
        return null;
      }
      current = ((Map<String, Object>) current).get(part);
    }
    return current;
  }

  /**
   * Remove a value from a nested map using dot notation.
   *
   * @param map the map to modify
   * @param path the dot-separated path (e.g. "a.b.c")
   */
  public static void removeNestedValue(final Map<String, Object> map, final String path) {
    if (map == null || path == null || path.isEmpty()) {
      return;
    }
    final String[] parts = path.split("\\.");
    Map<String, Object> current = map;
    for (int i = 0; i < parts.length - 1; i++) {
      final String part = parts[i];
      final Object next = current.get(part);
      if (next instanceof Map) {
        final Map<String, Object> mutableNext = new HashMap<>((Map<String, Object>) next);
        current.put(part, mutableNext);
        current = mutableNext;
      } else {
        return;
      }
    }
    current.remove(parts[parts.length - 1]);
  }

  /**
   * Flattens a nested Map into a single-level Map with dot-notation keys.
   *
   * @param source the source map to flatten
   * @return a new flattened map
   */
  public static Map<String, Object> flatten(final Map<String, Object> source) {
    final Map<String, Object> result = new HashMap<>();
    if (source != null) {
      flatten("", source, result);
    }
    return result;
  }

  private static void flatten(
      final String prefix, final Object value, final Map<String, Object> result) {
    if (value instanceof Map) {
      final Map<String, Object> map = (Map<String, Object>) value;
      map.forEach(
          (key, val) -> {
            final String newKey = prefix.isEmpty() ? key : prefix + "." + key;
            flatten(newKey, val, result);
          });
    } else {
      if (!prefix.isEmpty()) {
        result.put(prefix, value);
      }
    }
  }
}
