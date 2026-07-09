// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable log entry for plugin execution logs.
 *
 * <p>Captures a single log message with full execution context and metadata.
 */
public record PluginLogEntry(
    String executionId,
    String sessionId,
    String pluginId,
    String pluginName,
    LogStream stream,
    String message,
    LogLevel logLevel,
    Instant timestamp,
    String customStreamName,
    Map<String, Object> metadata) {

  /**
   * Compact constructor to ensure metadata defensively copied to prevent mutation after creation.
   * Also recursively freezes nested mutable collections to prevent external mutation leaks.
   */
  public PluginLogEntry {
    if (metadata == null) {
      metadata = Map.of();
    } else {
      metadata = freezeMetadata(metadata);
    }
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private static Map<String, Object> freezeMetadata(final Map<String, Object> original) {
    final Map<String, Object> frozen = new LinkedHashMap<>();
    original.forEach((key, value) -> frozen.put(key, freezeValue(value)));
    return Collections.unmodifiableMap(frozen);
  }

  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.UseConcurrentHashMap"})
  private static Object freezeValue(final Object value) {
    if (value instanceof List) {
      final List<?> list = (List<?>) value;
      final List<Object> frozenList = new ArrayList<>();
      for (final Object item : list) {
        frozenList.add(freezeValue(item));
      }
      return List.copyOf(frozenList);
    }
    if (value instanceof Set) {
      final Set<?> set = (Set<?>) value;
      final List<Object> frozenList = new ArrayList<>();
      for (final Object item : set) {
        frozenList.add(freezeValue(item));
      }
      return Set.copyOf(frozenList);
    }
    if (value instanceof Map) {
      final Map<?, ?> map = (Map<?, ?>) value;
      final Map<Object, Object> frozenMap = new LinkedHashMap<>();
      map.forEach((k, v) -> frozenMap.put(k, freezeValue(v)));
      return Collections.unmodifiableMap(frozenMap);
    }
    return value;
  }

  @Override
  public Map<String, Object> metadata() {
    return Map.copyOf(metadata);
  }

  /**
   * Format this log entry as a human-readable string.
   *
   * @return formatted log line
   */
  public String format() {
    final String streamDisplay =
        stream == LogStream.CUSTOM && customStreamName != null
            ? customStreamName
            : stream.toString();
    final StringBuilder stringBuilder =
        new StringBuilder()
            .append('[')
            .append(timestamp)
            .append("] [")
            .append(logLevel)
            .append("] [")
            .append(pluginId)
            .append('/')
            .append(pluginName)
            .append("] ")
            .append(streamDisplay)
            .append(": ")
            .append(message);

    if (!metadata.isEmpty()) {
      stringBuilder.append(" {");
      metadata.forEach(
          (key, value) -> stringBuilder.append(key).append('=').append(value).append(", "));
      stringBuilder.setLength(stringBuilder.length() - 2);
      stringBuilder.append('}');
    }

    return stringBuilder.toString();
  }
}
