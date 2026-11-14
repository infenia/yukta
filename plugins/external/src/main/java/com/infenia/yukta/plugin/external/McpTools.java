// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.core.ConfigField;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** What a {@code run.mcp} plugin takes from its tool: the configuration form and the pin. */
@UtilityClass
public class McpTools {

  /** Writes a schema the same way every time: keys sorted, no spaces. */
  private static final JsonMapper CANONICAL =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  /**
   * The configuration fields of a tool plugin: the manifest's own fields first (a {@code SECRET},
   * say), then one per property of the tool's input schema that the manifest does not declare.
   *
   * @param declared the manifest's fields
   * @param inputSchema the tool's {@code inputSchema}
   * @return the fields; the node configuration is the tool's arguments
   */
  @SuppressWarnings("unchecked")
  public static List<ConfigField> fields(
      final List<ConfigField> declared, final Map<String, Object> inputSchema) {
    final List<ConfigField> fields = new ArrayList<>(declared);
    final Set<String> names = declared.stream().map(ConfigField::name).collect(Collectors.toSet());
    final List<Object> required = (List<Object>) inputSchema.getOrDefault("required", List.of());
    final Map<String, Object> properties =
        (Map<String, Object>) inputSchema.getOrDefault("properties", Map.of());
    properties.forEach(
        (name, schema) -> {
          if (!names.contains(name) && schema instanceof Map<?, ?> property) {
            fields.add(field(name, (Map<String, Object>) property, required.contains(name)));
          }
        });
    return fields;
  }

  @SuppressWarnings("unchecked")
  private static ConfigField field(
      final String name, final Map<String, Object> property, final boolean required) {
    final List<String> options =
        ((List<Object>) property.getOrDefault("enum", List.of()))
            .stream().map(String::valueOf).toList();
    final ConfigField.Type type =
        options.isEmpty() ? typeOf(property.get("type")) : ConfigField.Type.ENUM;
    return new ConfigField(
        name,
        type,
        required,
        property.get("default"),
        options,
        String.valueOf(property.getOrDefault("description", "")));
  }

  private static ConfigField.Type typeOf(final Object jsonType) {
    return switch (String.valueOf(jsonType)) {
      case "number", "integer" -> ConfigField.Type.NUMBER;
      case "boolean" -> ConfigField.Type.BOOLEAN;
      case "array" -> ConfigField.Type.LIST;
      case "object" -> ConfigField.Type.MAP;
      default -> ConfigField.Type.STRING;
    };
  }

  /**
   * The pin of a tool plugin: its directory and the tool's input schema, so the server cannot
   * change the tool after the operator accepted it.
   *
   * @param dirDigest {@link PluginDigest#compute} of the plugin directory
   * @param inputSchema the tool's {@code inputSchema}
   * @return {@code sha256:<hex>} of the directory digest, a newline and the canonical schema
   */
  public static String digest(final String dirDigest, final Map<String, Object> inputSchema) {
    final String text = dirDigest + '\n' + CANONICAL.writeValueAsString(inputSchema);
    return "sha256:"
        + HexFormat.of()
            .formatHex(
                PluginDigest.digest("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
