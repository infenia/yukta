// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import org.springframework.ai.util.JsonHelper;

/**
 * Every tool result must pass the output check the MCP server runs before it answers: the server
 * turns the result into a map (Spring AI's {@link JsonHelper}) and validates it against the
 * generated output schema, so a {@code null} in a field the schema types as a plain string fails
 * the call ("output validation failed", as {@code list_pending_inputs} and {@code whoami} did).
 *
 * <p>For each result type of a tool with an output schema the test builds a full sample, then one
 * sample per {@link Nullable} record component (at any depth) set to null, and validates each the
 * way the server does.
 */
class ToolOutputSchemaTest {

  private static final JsonHelper JSON = new JsonHelper();
  private static final JsonSchemaValidator VALIDATOR = new DefaultJsonSchemaValidator();
  private static final int MAX_DEPTH = 6;

  /** A sample value and the path of the component it set to null ("" for the full sample). */
  private record Variant(String path, @Nullable Object value) {}

  @Test
  void everyNullableFieldOfEveryToolResultPassesTheOutputCheck() {
    final List<String> failures = new ArrayList<>();
    final List<Class<?>> types = resultTypes();
    assertThat(types).as("tools with an output schema").hasSizeGreaterThan(20);
    for (final Class<?> type : types) {
      final Map<String, Object> schema =
          JSON.fromJsonToMap(McpJsonSchemaGenerator.generateFromClass(type));
      for (final Variant v : variants(type, 0)) {
        final Map<String, Object> result = JSON.fromJsonToMap(JSON.toJson(v.value()));
        final JsonSchemaValidator.ValidationResponse r = VALIDATOR.validate(schema, result);
        if (!r.valid()) {
          failures.add(
              type.getSimpleName()
                  + (v.path().isEmpty() ? " (full sample)" : " with " + v.path() + " = null")
                  + ": "
                  + r.errorMessage());
        }
      }
    }
    assertThat(failures).isEmpty();
  }

  private static List<Class<?>> resultTypes() {
    return Arrays.stream(AppMcpTools.class.getMethods())
        .filter(
            m -> {
              final McpTool t = m.getAnnotation(McpTool.class);
              return t != null && t.generateOutputSchema();
            })
        .map(ToolOutputSchemaTest::monoType)
        .distinct()
        .toList();
  }

  private static Class<?> monoType(final Method m) {
    return raw(((ParameterizedType) m.getGenericReturnType()).getActualTypeArguments()[0]);
  }

  private static Class<?> raw(final Type t) {
    return t instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) t;
  }

  /** The full sample first, then one variant per nullable component at any depth. */
  private static List<Variant> variants(final Type type, final int depth) {
    final Class<?> cls = raw(type);
    final List<Variant> out = new ArrayList<>();
    out.add(new Variant("", sample(type, depth)));
    if (Collection.class.isAssignableFrom(cls) && type instanceof ParameterizedType p) {
      for (final Variant v : variants(p.getActualTypeArguments()[0], depth + 1)) {
        if (!v.path().isEmpty()) {
          out.add(new Variant("[]." + v.path(), cls == Set.class ? Set.of(v.value()) : list(v)));
        }
      }
    }
    if (!cls.isRecord() || depth > MAX_DEPTH) {
      return out;
    }
    final RecordComponent[] parts = cls.getRecordComponents();
    for (int i = 0; i < parts.length; i++) {
      final RecordComponent c = parts[i];
      if (c.isAnnotationPresent(Nullable.class)
          || c.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
        out.add(new Variant(c.getName(), build(cls, i, null, depth)));
      }
      for (final Variant v : variants(c.getGenericType(), depth + 1)) {
        if (!v.path().isEmpty()) {
          out.add(new Variant(c.getName() + "." + v.path(), build(cls, i, v.value(), depth)));
        }
      }
    }
    return out;
  }

  private static List<@Nullable Object> list(final Variant v) {
    final List<@Nullable Object> l = new ArrayList<>();
    l.add(v.value());
    return l;
  }

  /** The record built from full samples, with component {@code at} replaced by {@code value}. */
  private static Object build(
      final Class<?> cls, final int at, final @Nullable Object value, final int depth) {
    final RecordComponent[] parts = cls.getRecordComponents();
    final Object[] args = new Object[parts.length];
    final Class<?>[] types = new Class<?>[parts.length];
    for (int i = 0; i < parts.length; i++) {
      types[i] = parts[i].getType();
      args[i] = i == at ? value : sample(parts[i].getGenericType(), depth + 1);
    }
    try {
      final Constructor<?> ctor = cls.getDeclaredConstructor(types);
      ctor.setAccessible(true);
      return ctor.newInstance(args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot build " + cls.getName(), e);
    }
  }

  /** A non-null value of the type, as close to a real result as plain data allows. */
  private static @Nullable Object sample(final Type type, final int depth) {
    final Class<?> cls = raw(type);
    if (cls == String.class || cls == Object.class) {
      return "x";
    }
    if (cls == boolean.class || cls == Boolean.class) {
      return true;
    }
    if (cls == int.class || cls == Integer.class) {
      return 1;
    }
    if (cls == long.class || cls == Long.class) {
      return 1L;
    }
    if (cls == double.class || cls == Double.class) {
      return 1.0;
    }
    if (cls == Instant.class) {
      return Instant.parse("2026-01-01T00:00:00Z");
    }
    if (cls == Duration.class) {
      return Duration.ofSeconds(1);
    }
    if (cls.isEnum()) {
      return cls.getEnumConstants()[0];
    }
    if (Map.class.isAssignableFrom(cls)) {
      return Map.of();
    }
    if (Collection.class.isAssignableFrom(cls)) {
      if (depth > MAX_DEPTH || !(type instanceof ParameterizedType p)) {
        return List.of();
      }
      final Object e = sample(p.getActualTypeArguments()[0], depth + 1);
      if (e == null) {
        return List.of();
      }
      return cls == Set.class ? Set.of(e) : List.of(e);
    }
    if (cls.isRecord() && depth <= MAX_DEPTH) {
      return build(cls, -1, null, depth);
    }
    // another class: Jackson's view of an empty object is the closest plain sample
    return JSON.fromJson("{}", cls);
  }
}
