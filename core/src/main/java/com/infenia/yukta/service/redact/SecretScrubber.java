// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Masks the secret values that were resolved ({@code decrypted:name}) wherever they show up in a
 * log line, a node's status (its error) or a result, also base64 or URL-encoded. A value shorter
 * than {@link #MIN_LENGTH} is not masked, to keep ordinary words out of it.
 *
 * <p>Limits: the values are kept for the life of the process and are masked in every run, not only
 * the one that resolved them (a run cannot be named from the secret lookup). A plugin that
 * transforms a secret (hashes, splits or re-encodes it) cannot be caught. Only values read by the
 * default environment provider are known.
 */
@Component
public class SecretScrubber {

  /** The shortest value that is masked. */
  public static final int MIN_LENGTH = 6;

  /** How deep a result or metadata value is walked; deeper values are left as they are. */
  private static final int MAX_DEPTH = 32;

  /** What replaces a secret. */
  public static final String MASK = "******";

  /** The values and their encodings, longest first so a longer one is not cut by a shorter one. */
  private final Set<String> values =
      new ConcurrentSkipListSet<>(
          Comparator.comparingInt(String::length)
              .reversed()
              .thenComparing(Comparator.naturalOrder()));

  /** Lines in which something was masked. */
  private final Counter masked;

  /**
   * Creates a scrubber.
   *
   * @param meters where {@code yukta.secrets.masked} is counted
   */
  public SecretScrubber(final MeterRegistry meters) {
    this.masked =
        Counter.builder("yukta.secrets.masked")
            .description("Log lines in which a resolved secret was masked")
            .register(meters);
  }

  /**
   * Remembers a resolved secret.
   *
   * @param value the secret, ignored when null or too short
   */
  public void record(final String value) {
    if (value == null || value.length() < MIN_LENGTH) {
      return;
    }
    values.add(value);
    values.add(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
    values.add(URLEncoder.encode(value, StandardCharsets.UTF_8));
  }

  /**
   * Masks every known secret in a text.
   *
   * @param text the text
   * @return the text with each secret replaced by {@link #MASK}
   */
  public String scrub(final String text) {
    if (values.isEmpty()) {
      return text;
    }
    String result = text;
    for (final String value : values) {
      result = result.replace(value, MASK);
    }
    if (!result.equals(text)) {
      masked.increment();
    }
    return result;
  }

  /**
   * Masks every known secret in the text of a value: a string, or the strings inside a map or list
   * (nested), as results and status metadata hold them. Other values are left as they are.
   *
   * @param value the value
   * @return the value with its secrets masked; the same instance when there was nothing to mask
   */
  public @Nullable Object scrubValue(final @Nullable Object value) {
    return values.isEmpty() ? value : scrubValue(value, 0);
  }

  private @Nullable Object scrubValue(final @Nullable Object value, final int depth) {
    if (depth > MAX_DEPTH) {
      return value;
    }
    if (value instanceof final String text) {
      return scrub(text);
    }
    if (value instanceof final Map<?, ?> map) {
      return scrubEntries(map, depth);
    }
    if (value instanceof final List<?> list) {
      return scrubItems(list, depth);
    }
    return value;
  }

  private Map<?, ?> scrubEntries(final Map<?, ?> map, final int depth) {
    final Map<Object, Object> copy = newOrderedMap();
    boolean changed = false;
    for (final Map.Entry<?, ?> entry : map.entrySet()) {
      final Object scrubbed = scrubValue(entry.getValue(), depth + 1);
      changed |= !Objects.equals(scrubbed, entry.getValue());
      copy.put(entry.getKey(), scrubbed);
    }
    return changed ? Collections.unmodifiableMap(copy) : map;
  }

  /** An order-keeping map; unlike a concurrent map it accepts null values. */
  private static Map<Object, Object> newOrderedMap() {
    return new LinkedHashMap<>();
  }

  private List<?> scrubItems(final List<?> list, final int depth) {
    final List<Object> copy = new ArrayList<>(list.size());
    boolean changed = false;
    for (final Object item : list) {
      final Object scrubbed = scrubValue(item, depth + 1);
      changed |= !Objects.equals(scrubbed, item);
      copy.add(scrubbed);
    }
    return changed ? Collections.unmodifiableList(copy) : list;
  }

  /**
   * The same for a map of metadata.
   *
   * @param metadata the metadata
   * @return the metadata with its secrets masked; the same instance when there was nothing to mask
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> scrubMap(final Map<String, Object> metadata) {
    return (Map<String, Object>) Objects.requireNonNull(scrubValue(metadata));
  }
}
