// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The frames of {@code yukta-cli pty-host}: one JSON object per line, {@code t} names the kind. The
 * server sends {@code in} (keys, base64), {@code resize} and {@code close}; the host sends {@code
 * out} (output, base64), {@code screen} (the settled screen as text) and {@code exit}.
 */
final class PtyFrames {

  /** The key of the frame kind. */
  private static final String T = "t";

  private final ObjectMapper json;

  /* default */ PtyFrames(final ObjectMapper json) {
    this.json = json;
  }

  /** A frame the host sent. */
  /* default */ record Host(
      String kind,
      byte @Nullable [] data,
      String text,
      int cols,
      int rows,
      int cursorRow,
      int cursorCol,
      int code) {}

  /**
   * Reads a line of the host.
   *
   * @param line the line
   * @return the frame, or null when the line is not a frame
   */
  /* default */ @Nullable Host parse(final String line) {
    final JsonNode node;
    try {
      node = json.readTree(line);
    } catch (JacksonException e) {
      return null;
    }
    if (!node.isObject() || !node.path(T).isString()) {
      return null;
    }
    final String kind = node.path(T).asString();
    return switch (kind) {
      case "out" -> {
        final byte[] bytes = decode(node.path("d").asString(""));
        yield bytes.length == 0 ? null : new Host(kind, bytes, "", 0, 0, 0, 0, 0);
      }
      case "screen" ->
          new Host(
              kind,
              null,
              node.path("text").asString(""),
              node.path("cols").asInt(0),
              node.path("rows").asInt(0),
              node.path("cursorRow").asInt(0),
              node.path("cursorCol").asInt(0),
              0);
      case "exit" -> new Host(kind, null, "", 0, 0, 0, 0, node.path("code").asInt(-1));
      default -> null;
    };
  }

  private static byte[] decode(final String encoded) {
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  /* default */ String keys(final byte[] data) {
    return write(Map.of(T, "in", "d", Base64.getEncoder().encodeToString(data)));
  }

  /* default */ String keys(final String text) {
    return keys(text.getBytes(StandardCharsets.UTF_8));
  }

  /* default */ String resize(final int cols, final int rows) {
    return write(Map.of(T, "resize", "cols", cols, "rows", rows));
  }

  /* default */ String close() {
    return write(Map.of(T, "close"));
  }

  private String write(final Map<String, Object> frame) {
    return json.writeValueAsString(new LinkedHashMap<>(frame));
  }
}
