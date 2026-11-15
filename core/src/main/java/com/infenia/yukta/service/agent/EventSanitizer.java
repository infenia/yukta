// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.service.redact.InputRedactor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies the limits and the redaction to the data of an event before it is stored: text is cut at
 * {@code max-message-bytes}, tool input and output at {@code max-tool-bytes}, a terminal chunk at
 * 16 KB (a longer one becomes several), and secrets are replaced everywhere but in terminal bytes.
 * Whatever is cut gets {@code truncated: true}.
 */
@SuppressWarnings({
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidInstantiatingObjectsInLoops"
}) // local maps, never shared
final class EventSanitizer {

  /** The largest terminal chunk of one event, in bytes. */
  /* default */ static final int TERMINAL_CHUNK_BYTES = 16 * 1024;

  /** The data key that flags cut content. */
  /* default */ static final String TRUNCATED = "truncated";

  /** Removes secrets. */
  private final InputRedactor redactor;

  /** Turns structured tool input into text when it is too long. */
  private final ObjectMapper json;

  /** Longest text of one event, in bytes. */
  private final int maxMessageBytes;

  /** Longest tool input or output, in bytes. */
  private final int maxToolBytes;

  /* default */ EventSanitizer(
      final InputRedactor redactor,
      final ObjectMapper json,
      final int maxMessageBytes,
      final int maxToolBytes) {
    this.redactor = redactor;
    this.json = json;
    this.maxMessageBytes = maxMessageBytes;
    this.maxToolBytes = maxToolBytes;
  }

  /**
   * Cleans the data of an event.
   *
   * @param kind the kind of the event
   * @param data the data as the plugin gave it
   * @return one data map, or several for a terminal chunk that was too long, or none for terminal
   *     output that is not base64
   */
  /* default */ List<Map<String, Object>> sanitize(
      final AgentEventKind kind, final Map<String, Object> data) {
    if (kind == AgentEventKind.TERMINAL) {
      return terminal(data);
    }
    final Map<String, Object> clean = new LinkedHashMap<>();
    boolean truncated = false;
    for (final Map.Entry<String, Object> entry : data.entrySet()) {
      final Limited limited = clean(kind, entry.getKey(), entry.getValue());
      clean.put(entry.getKey(), limited.value);
      truncated |= limited.cut;
    }
    if (truncated) {
      clean.put(TRUNCATED, true);
    }
    return List.of(clean);
  }

  /**
   * Redacts and cuts one text, for the reason of a state or an outcome.
   *
   * @param text the text, or null
   * @return the cleaned text, or null
   */
  /* default */ @Nullable String text(final @Nullable String text) {
    return text == null ? null : (String) limit(redactor.text(text), maxMessageBytes).value();
  }

  /** Cleans one entry of the data of an event. */
  private Limited clean(final AgentEventKind kind, final String key, final Object value) {
    if (kind == AgentEventKind.TOOL && ("input".equals(key) || "output".equals(key))) {
      return tool(value);
    }
    if (kind == AgentEventKind.PLAN && "entries".equals(key)) {
      return new Limited(plan(value), false);
    }
    if (value instanceof final String text) {
      return limit(redactor.text(text), maxMessageBytes);
    }
    return new Limited(value, false);
  }

  private List<Map<String, Object>> terminal(final Map<String, Object> data) {
    if (data.containsKey("screen")) {
      return List.of(data);
    }
    if (!(data.get("data") instanceof final String encoded)) {
      return List.of();
    }
    final byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      return List.of();
    }
    if (bytes.length <= TERMINAL_CHUNK_BYTES) {
      return List.of(data);
    }
    final List<Map<String, Object>> chunks = new ArrayList<>();
    for (int from = 0; from < bytes.length; from += TERMINAL_CHUNK_BYTES) {
      final int to = Math.min(bytes.length, from + TERMINAL_CHUNK_BYTES);
      final Map<String, Object> chunk = new LinkedHashMap<>(data);
      chunk.put("data", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, from, to)));
      chunks.add(chunk);
    }
    return chunks;
  }

  private List<Object> plan(final Object entries) {
    if (!(entries instanceof final List<?> list)) {
      return List.of();
    }
    final List<Object> clean = new ArrayList<>();
    for (final Object item : list) {
      if (item instanceof final Map<?, ?> map) {
        final Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) ->
            copy.put(
                String.valueOf(key),
                value instanceof final String text
                    ? limit(redactor.text(text), maxToolBytes).value
                    : value));
        clean.add(copy);
      } else {
        clean.add(item);
      }
    }
    return clean;
  }

  /** Cleans tool input or output: a map that fits stays a map, anything else becomes text. */
  private Limited tool(final Object value) {
    if (value instanceof final Map<?, ?> map) {
      final Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
      final Map<String, Object> redacted = redactor.map(copy);
      final String text = asText(redacted);
      return text.getBytes(StandardCharsets.UTF_8).length <= maxToolBytes
          ? new Limited(redacted, false)
          : limit(text, maxToolBytes);
    }
    final String text = value instanceof final String plain ? plain : asText(value);
    return limit(redactor.text(text), maxToolBytes);
  }

  private String asText(final Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (JacksonException e) {
      return String.valueOf(value);
    }
  }

  /** Cuts a text to a number of bytes, at the end of a character. */
  /* default */ static Limited limit(final String text, final int maxBytes) {
    if (text.length() * 3L <= maxBytes
        || text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
      return new Limited(text, false);
    }
    int bytes = 0;
    int end = 0;
    while (end < text.length()) {
      final int codePoint = text.codePointAt(end);
      final int size =
          new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
      if (bytes + size > maxBytes) {
        break;
      }
      bytes += size;
      end += Character.charCount(codePoint);
    }
    return new Limited(text.substring(0, end), true);
  }

  /**
   * A value and whether it was cut.
   *
   * @param value the value
   * @param cut true if content was dropped
   */
  /* default */ record Limited(Object value, boolean cut) {}
}
