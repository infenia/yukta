// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginCategory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.With;

/**
 * A community plugin's {@code yukta-plugin.json}, read after it passed {@code
 * manifest.schema.json}, so the shape is already checked here.
 *
 * @param type the node type
 * @param category processor or terminal
 * @param description one line for listings
 * @param config the configuration fields
 * @param ports the output ports of a processor; empty for a terminal
 * @param timeout how long one message may take
 * @param command the argv of {@code run.command}; empty for another run form
 * @param image the image of {@code run.image}; empty for another run form
 * @param runForm the run form: {@code command}, {@code image} or {@code mcp}
 * @param dir the plugin's directory
 */
@With
public record PluginManifest(
    String type,
    PluginCategory category,
    String description,
    List<ConfigField> config,
    List<String> ports,
    Duration timeout,
    List<String> command,
    String image,
    String runForm,
    Path dir) {

  /** The timeout of a manifest that sets none. */
  /* default */ static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** Copies the lists, so the record is immutable. */
  public PluginManifest {
    config = List.copyOf(config);
    ports = List.copyOf(ports);
    command = List.copyOf(command);
  }

  /**
   * Builds the manifest from its validated JSON.
   *
   * @param json the manifest, valid against the schema
   * @param dir the plugin's directory
   * @return the manifest
   */
  @SuppressWarnings("unchecked")
  /* default */ static PluginManifest from(final Map<String, Object> json, final Path dir) {
    final PluginCategory category =
        PluginCategory.valueOf(((String) json.get("category")).toUpperCase(Locale.ROOT));
    final Map.Entry<String, Object> run =
        ((Map<String, Object>) json.get("run")).entrySet().iterator().next();
    final String form = run.getKey();
    final List<String> ports =
        category == PluginCategory.TERMINAL
            ? List.of()
            : (List<String>) json.getOrDefault("ports", List.of("default"));
    return new PluginManifest(
        (String) json.get("type"),
        category,
        (String) json.get("description"),
        ((List<Map<String, Object>>) json.getOrDefault("config", List.of()))
            .stream().map(PluginManifest::field).toList(),
        ports,
        json.containsKey("timeout") ? duration((String) json.get("timeout")) : DEFAULT_TIMEOUT,
        "command".equals(form) ? (List<String>) run.getValue() : List.of(),
        "image".equals(form) ? (String) run.getValue() : "",
        form,
        dir);
  }

  @SuppressWarnings("unchecked")
  private static ConfigField field(final Map<String, Object> f) {
    return new ConfigField(
        (String) f.get("name"),
        ConfigField.Type.valueOf((String) f.get("type")),
        Boolean.TRUE.equals(f.get("required")),
        f.get("default"),
        (List<String>) f.getOrDefault("options", List.of()),
        (String) f.getOrDefault("description", ""));
  }

  /** {@code 500ms}, {@code 30s}, {@code 5m}, {@code 1h}: the schema's pattern. */
  private static Duration duration(final String text) {
    final int unit = text.endsWith("ms") ? text.length() - 2 : text.length() - 1;
    final long n = Long.parseLong(text.substring(0, unit));
    return switch (text.substring(unit)) {
      case "ms" -> Duration.ofMillis(n);
      case "s" -> Duration.ofSeconds(n);
      case "m" -> Duration.ofMinutes(n);
      default -> Duration.ofHours(n);
    };
  }
}
