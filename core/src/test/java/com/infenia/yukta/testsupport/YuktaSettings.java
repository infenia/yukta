// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import com.infenia.yukta.config.YuktaProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Binds {@code yukta.*} settings the way the application does, for the Spring constructors. */
public final class YuktaSettings {

  private YuktaSettings() {}

  /**
   * The settings with the given overrides applied over the defaults.
   *
   * @param overrides property names such as {@code yukta.stop.drain-timeout} and their values
   * @return the bound settings
   */
  public static YuktaProperties bind(final Map<String, String> overrides) {
    // The binder reports nothing bound for an empty source, so one property at its default value
    // is set first; every other unset property then takes its default.
    final Map<String, String> values = new ConcurrentHashMap<>();
    values.put("yukta.execution.max-concurrent", "0");
    values.putAll(overrides);
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("yukta", YuktaProperties.class)
        .get();
  }
}
