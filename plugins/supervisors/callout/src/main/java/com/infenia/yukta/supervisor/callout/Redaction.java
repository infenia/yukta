// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.callout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/** Masks secret-looking values in what is sent to the webhook, at any depth. */
@UtilityClass
@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName", "PMD.UseConcurrentHashMap"})
public class Redaction {

  /** What replaces a secret value. */
  public static final String MASK = "***";

  private static final Pattern SECRET =
      Pattern.compile("(?i).*(key|secret|token|password|credential|authorization).*");

  /**
   * Copies a value with the values under secret-looking keys masked.
   *
   * @param value a map, a list or a plain value
   * @return the copy
   */
  public static Object mask(final Object value) {
    if (value instanceof final Map<?, ?> map) {
      final Map<String, Object> out = new LinkedHashMap<>();
      map.forEach(
          (key, inner) ->
              out.put(
                  String.valueOf(key),
                  SECRET.matcher(String.valueOf(key)).matches() ? MASK : mask(inner)));
      return out;
    }
    if (value instanceof final List<?> list) {
      final List<Object> out = new ArrayList<>();
      list.forEach(inner -> out.add(mask(inner)));
      return out;
    }
    return value;
  }
}
