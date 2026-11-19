// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.springframework.stereotype.Component;

/**
 * Masks the secret values that were resolved ({@code decrypted:name}) wherever they show up in a
 * log line, also base64 or URL-encoded. A value shorter than {@link #MIN_LENGTH} is not masked, to
 * keep ordinary words out of it.
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
    String result = text;
    for (final String value : values) {
      result = result.replace(value, MASK);
    }
    if (!result.equals(text)) {
      masked.increment();
    }
    return result;
  }
}
