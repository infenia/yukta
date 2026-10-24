// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

/**
 * Checks every registered plugin against the plugin contract, so a new plugin is checked from its
 * first day without a new test: nothing here names a plugin. A rule that a plugin breaks on purpose
 * goes in {@link #EXEMPT} with the reason; an exemption that no longer applies fails the test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "yukta.process.allow-any=true")
@SuppressWarnings({"PMD.CommentRequired", "PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@NoArgsConstructor
class PluginConformanceAcceptanceTest {

  /** "TYPE: rule" to the reason the plugin is allowed to break the rule. */
  private static final Map<String, String> EXEMPT = Map.of();

  private static final Duration WAIT = Duration.ofSeconds(5);

  @Autowired private PluginRegistry registry;

  @Test
  void everyRegisteredPlugin_followsThePluginContract() {
    final Set<String> found = new TreeSet<>();
    for (final Plugin plugin : registry.listPlugins()) {
      for (final String rule : violations(plugin)) {
        found.add(plugin.getType() + ": " + rule);
      }
    }
    final Set<String> unexpected = new TreeSet<>(found);
    unexpected.removeAll(EXEMPT.keySet());
    final Set<String> stale = new TreeSet<>(EXEMPT.keySet());
    stale.removeAll(found);
    assertThat(unexpected)
        .as("plugin contract violations (fix them or add an EXEMPT entry)")
        .isEmpty();
    assertThat(stale).as("EXEMPT entries that no longer apply (delete them)").isEmpty();
  }

  private static List<String> violations(final Plugin plugin) {
    final List<String> rules = new ArrayList<>();
    final String type = plugin.getType();
    if (type == null || !type.matches("[A-Z][A-Z0-9_]*")) {
      rules.add("type is not UPPER_SNAKE_CASE: " + type);
    }
    if (plugin.getCategory() == null) {
      rules.add("category is null");
    }
    rules.addAll(schemaViolations(plugin.getConfigSchema()));
    rules.addAll(portViolations("output ports", plugin.getOutputPorts()));
    rules.addAll(
        portViolations("output ports for an empty config", plugin.getOutputPorts(Map.of())));
    if (plugin.getDefaultTimeout().isZero() || plugin.getDefaultTimeout().isNegative()) {
      rules.add("default timeout is not positive");
    }
    if (plugin.getDefaultBufferSize() <= 0) {
      rules.add("default buffer size is not positive");
    }
    if (plugin.describe() == null || plugin.describe().description().isBlank()) {
      rules.add("describe() has no description");
    }
    rules.addAll(configViolations(plugin));
    return rules;
  }

  private static List<String> schemaViolations(final List<ConfigField> schema) {
    final List<String> rules = new ArrayList<>();
    final Set<String> names = new HashSet<>();
    for (final ConfigField field : schema) {
      if (field.name() == null || field.name().isBlank()) {
        rules.add("a config field has no name");
      } else if (!names.add(field.name())) {
        rules.add("config field declared twice: " + field.name());
      }
      if (field.type() == ConfigField.Type.ENUM && field.options().isEmpty()) {
        rules.add("enum config field has no options: " + field.name());
      }
    }
    return rules;
  }

  private static List<String> portViolations(final String what, final List<String> ports) {
    final List<String> rules = new ArrayList<>();
    if (ports.isEmpty()) {
      rules.add(what + " are empty");
    }
    if (ports.stream().anyMatch(String::isBlank)) {
      rules.add(what + " hold a blank name");
    }
    if (new HashSet<>(ports).size() != ports.size()) {
      rules.add(what + " hold a duplicate");
    }
    return rules;
  }

  private static List<String> configViolations(final Plugin plugin) {
    final List<String> rules = new ArrayList<>();
    final String empty = outcome(() -> plugin.validateConfig(Map.of()));
    final String unknown = outcome(() -> plugin.validateConfig(Map.of("noSuchField", "x")));
    final String shutdown = outcome(() -> plugin.shutdown(Map.of()));
    final boolean needsConfig = plugin.getConfigSchema().stream().anyMatch(ConfigField::required);
    if (needsConfig && "accepted".equals(empty)) {
      rules.add("validateConfig accepts an empty config although a field is required");
    }
    for (final String result : List.of(empty, unknown)) {
      if (result.startsWith("crashed")) {
        rules.add("validateConfig " + result);
      }
    }
    if (shutdown.startsWith("crashed")) {
      rules.add("shutdown before initialize " + shutdown);
    }
    return rules.stream().distinct().toList();
  }

  /** "accepted", "rejected" (a validation error), or "crashed with X" (NPE and the like). */
  private static String outcome(final Supplier<Mono<Void>> call) {
    String result = "accepted";
    try {
      call.get().block(WAIT);
    } catch (RuntimeException e) {
      final Throwable cause = e.getCause() == null ? e : e.getCause();
      final boolean crash =
          cause instanceof NullPointerException
              || cause instanceof ClassCastException
              || cause instanceof IndexOutOfBoundsException;
      result = crash ? "crashed with " + cause.getClass().getSimpleName() : "rejected";
    }
    return result;
  }
}
