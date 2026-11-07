// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.service.workflow.ConfigRedactor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/** Hides secret supervisor settings in what the API shows, and takes them back on update. */
@UtilityClass
public class SupervisorSettingsMask {

  /** What replaces a secret value. */
  public static final String HIDDEN = "***";

  /** Keys whose values are not shown, besides the SECRET fields of the type's schema. */
  private static final Pattern SECRET =
      Pattern.compile("(?i).*(key|secret|token|password|credential).*");

  /**
   * Replaces the values under secret-looking keys and the type's SECRET fields, at any depth.
   *
   * @param settings the settings
   * @param secrets the names of the type's SECRET fields
   * @return the settings as shown; null values are left out
   */
  public static Map<String, Object> hide(
      final Map<String, Object> settings, final Set<String> secrets) {
    final Map<String, Object> out = new ConcurrentHashMap<>();
    settings.forEach(
        (key, value) -> {
          if (value == null) {
            return;
          }
          out.put(
              key,
              SECRET.matcher(key).matches()
                      || ConfigRedactor.isSecretName(key)
                      || secrets.contains(key)
                  ? HIDDEN
                  : deep(value));
        });
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Object deep(final Object value) {
    if (value instanceof Map<?, ?> nested) {
      return hide((Map<String, Object>) nested, Set.of());
    }
    if (value instanceof List<?> items) {
      return items.stream().map(SupervisorSettingsMask::deep).toList();
    }
    return value;
  }

  /**
   * Puts the stored value back where the incoming settings carry the hidden marker.
   *
   * @param incoming the settings sent by a client
   * @param stored the settings kept
   * @return the settings to save
   */
  @SuppressWarnings({"unchecked", "PMD.UseConcurrentHashMap"}) // a local map; a setting may be null
  public static Map<String, Object> keepHidden(
      final Map<String, Object> incoming, final Map<String, Object> stored) {
    final Map<String, Object> out = new LinkedHashMap<>();
    incoming.forEach(
        (key, value) -> {
          final Object before = stored.get(key);
          if (HIDDEN.equals(value) && before != null) {
            out.put(key, before);
          } else if (value instanceof Map<?, ?> nested && before instanceof Map<?, ?> old) {
            out.put(key, keepHidden((Map<String, Object>) nested, (Map<String, Object>) old));
          } else {
            out.put(key, value);
          }
        });
    return out;
  }
}
