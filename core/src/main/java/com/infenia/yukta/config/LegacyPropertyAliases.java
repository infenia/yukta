// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Keeps the settings that moved under {@code yukta.*} working under their old names for one
 * release. A new name that is also set wins. The {@code .ms} values were integers of milliseconds;
 * they get the {@code ms} suffix so they bind as durations.
 */
@NoArgsConstructor
public class LegacyPropertyAliases implements EnvironmentPostProcessor {

  /** Old name to new name. */
  private static final Map<String, String> RENAMED =
      Map.of(
          "control.bus.batch.size", "yukta.control-bus.batch-size",
          "control.bus.buffer.size", "yukta.control-bus.buffer-size",
          "workflow.cache.max-size", "yukta.cache.max-size");

  /** Old names of millisecond integers, to new names. */
  private static final Map<String, String> RENAMED_MILLIS =
      Map.of(
          "control.bus.batch.timeout.ms", "yukta.control-bus.batch-timeout",
          "workflow.cache.ttl.ms", "yukta.cache.ttl");

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment, final SpringApplication application) {
    final Map<String, Object> aliases = new ConcurrentHashMap<>();
    RENAMED.forEach((old, name) -> alias(environment, aliases, old, name, ""));
    RENAMED_MILLIS.forEach((old, name) -> alias(environment, aliases, old, name, "ms"));
    if (!aliases.isEmpty()) {
      environment.getPropertySources().addLast(new MapPropertySource("yukta-legacy", aliases));
    }
  }

  private static void alias(
      final ConfigurableEnvironment environment,
      final Map<String, Object> aliases,
      final String old,
      final String name,
      final String unit) {
    final String value = environment.getProperty(old);
    if (value != null) {
      aliases.put(name, value + unit);
    }
  }
}
