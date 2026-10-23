// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Estimates the size of a payload without serialising it. */
@UtilityClass
final class PayloadSize {

  /**
   * Returns the bytes held by strings and byte arrays, also inside maps and collections.
   *
   * <p>ponytail: other objects count 0, so a plugin POJO holding a large body is not caught; count
   * the JSON size if that happens.
   */
  /* default */ static long bytesIn(final Object value) {
    return switch (value) {
      case CharSequence text -> text.toString().getBytes(StandardCharsets.UTF_8).length;
      case byte[] bytes -> bytes.length;
      case Map<?, ?> map ->
          map.entrySet().stream().mapToLong(e -> bytesIn(e.getKey()) + bytesIn(e.getValue())).sum();
      case Iterable<?> items -> {
        long sum = 0;
        for (final Object item : items) {
          sum += bytesIn(item);
        }
        yield sum;
      }
      case null, default -> 0;
    };
  }
}
