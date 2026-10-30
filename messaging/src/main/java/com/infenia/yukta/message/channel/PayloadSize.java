// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/** Estimates the size of a payload without serialising it. */
@UtilityClass
final class PayloadSize {

  /** Code points below this need one byte in UTF-8. */
  private static final int ONE_BYTE_LIMIT = 0x80;

  /** Code points below this need two bytes. */
  private static final int TWO_BYTE_LIMIT = 0x800;

  /** Code points below this need three bytes; the rest need four. */
  private static final int THREE_BYTE_LIMIT = 0x10000;

  /**
   * Returns the bytes held by strings, byte arrays and buffers, also inside maps, collections,
   * object arrays and optionals. A container met twice (a cycle, or one shared value) counts once.
   *
   * <p>Records are walked through their components. ponytail: other objects (a plain POJO) count 0,
   * so one holding a large body is not caught; count the JSON size if that happens. A lazy {@code
   * Iterable} that is not a {@code Collection} (a {@code Path}, a one-shot stream view) is not
   * walked: doing so could recurse forever or consume it before the plugin sees it.
   */
  /* default */ static long bytesIn(final Object value) {
    return bytesIn(value, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  private static long bytesIn(final Object value, final Set<Object> seen) {
    return switch (value) {
      case CharSequence text -> utf8Length(text);
      case byte[] bytes -> bytes.length;
      case ByteBuffer buffer -> buffer.remaining();
      case Optional<?> optional -> optional.map(item -> bytesIn(item, seen)).orElse(0L);
      case Map<?, ?> map when seen.add(map) -> mapBytes(map, seen);
      case Object[] items when seen.add(items) -> sumOf(Arrays.asList(items), seen);
      case Collection<?> items when seen.add(items) -> sumOf(items, seen);
      case Record record when seen.add(record) -> recordBytes(record, seen);
      case null, default -> 0;
    };
  }

  private static long mapBytes(final Map<?, ?> map, final Set<Object> seen) {
    long sum = 0;
    for (final Map.Entry<?, ?> entry : map.entrySet()) {
      sum += bytesIn(entry.getKey(), seen) + bytesIn(entry.getValue(), seen);
    }
    return sum;
  }

  private static long recordBytes(final Record record, final Set<Object> seen) {
    long sum = 0;
    for (final RecordComponent component : record.getClass().getRecordComponents()) {
      try {
        // a component that cannot be read (a non-public type, or an accessor that throws) counts 0
        final Method accessor = component.getAccessor();
        sum += bytesIn(accessor.invoke(record), seen);
      } catch (ReflectiveOperationException e) {
        continue;
      }
    }
    return sum;
  }

  private static long sumOf(final Iterable<?> items, final Set<Object> seen) {
    long sum = 0;
    for (final Object item : items) {
      sum += bytesIn(item, seen);
    }
    return sum;
  }

  /** UTF-8 length of the text without making a copy of it. */
  private static long utf8Length(final CharSequence text) {
    return text.codePoints().mapToLong(PayloadSize::utf8Length).sum();
  }

  private static int utf8Length(final int codePoint) {
    return codePoint < ONE_BYTE_LIMIT
        ? 1
        : codePoint < TWO_BYTE_LIMIT ? 2 : codePoint < THREE_BYTE_LIMIT ? 3 : 4;
  }
}
