// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks structured answer data against the part of JSON Schema that a {@code responseSchema} may
 * use: {@code type} (object, array, string, number, integer, boolean, null), {@code properties},
 * {@code required}, {@code enum}, {@code items} and {@code additionalProperties: false}. Any other
 * keyword is ignored. Messages name the path and the failed keyword and never repeat a value, so a
 * secret in an answer does not reach an error message.
 */
@UtilityClass
public class SchemaSubset {

  /** Reads schemas and turns data into a tree. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The ways the data does not fit the schema.
   *
   * @param schema the schema, a JSON object as text
   * @param data the answer data
   * @return the violations, each naming a path; empty when the data fits
   */
  public static List<String> violations(final String schema, final Map<String, Object> data) {
    final List<String> found = new ArrayList<>();
    check(JSON.readTree(schema), JSON.valueToTree(data), "$", found);
    return found;
  }

  private static void check(
      final JsonNode schema, final JsonNode value, final String path, final List<String> found) {
    if (!checkType(schema, value, path, found)) {
      return;
    }
    checkEnum(schema, value, path, found);
    if (value.isObject()) {
      checkRequired(schema, value, path, found);
      checkFields(schema, value, path, found);
    }
    if (value.isArray()) {
      checkItems(schema, value, path, found);
    }
  }

  /**
   * Records a type mismatch; false when the value is of the wrong type, so nothing below applies.
   */
  private static boolean checkType(
      final JsonNode schema, final JsonNode value, final String path, final List<String> found) {
    final JsonNode type = schema.get("type");
    final boolean named = type != null && type.isString();
    final boolean fits = !named || isOfType(type.stringValue(), value);
    if (!fits) {
      found.add(path + ": expected " + type.stringValue());
    }
    return fits;
  }

  private static void checkEnum(
      final JsonNode schema, final JsonNode value, final String path, final List<String> found) {
    final JsonNode allowed = schema.get("enum");
    if (allowed != null && allowed.isArray() && !contains(allowed, value)) {
      found.add(path + ": not one of the allowed values");
    }
  }

  private static void checkRequired(
      final JsonNode schema, final JsonNode value, final String path, final List<String> found) {
    final JsonNode required = schema.get("required");
    if (required != null && required.isArray()) {
      for (final JsonNode name : required) {
        if (name.isString() && !value.has(name.stringValue())) {
          found.add(path + "." + name.stringValue() + ": required");
        }
      }
    }
  }

  private static void checkFields(
      final JsonNode schema, final JsonNode value, final String path, final List<String> found) {
    final JsonNode properties = schema.path("properties");
    final JsonNode extra = schema.path("additionalProperties");
    final boolean closed = extra.isBoolean() && !extra.booleanValue();
    for (final Map.Entry<String, JsonNode> field : value.properties()) {
      final JsonNode sub = properties.path(field.getKey());
      if (sub.isObject()) {
        check(sub, field.getValue(), path + "." + field.getKey(), found);
      } else if (closed) {
        found.add(path + "." + field.getKey() + ": not allowed");
      }
    }
  }

  private static void checkItems(
      final JsonNode schema, final JsonNode value, final String path, final List<String> found) {
    final JsonNode items = schema.path("items");
    if (items.isObject()) {
      for (int i = 0; i < value.size(); i++) {
        check(items, value.get(i), path + "[" + i + "]", found);
      }
    }
  }

  private static boolean isOfType(final String type, final JsonNode value) {
    return switch (type) {
      case "object" -> value.isObject();
      case "array" -> value.isArray();
      case "string" -> value.isString();
      case "number" -> value.isNumber();
      case "integer" -> value.isIntegralNumber();
      case "boolean" -> value.isBoolean();
      case "null" -> value.isNull();
      default -> true;
    };
  }

  private static boolean contains(final JsonNode allowed, final JsonNode value) {
    for (final JsonNode candidate : allowed) {
      if (candidate.equals(value)) {
        return true;
      }
    }
    return false;
  }
}
