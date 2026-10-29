// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for PayloadSize. */
@NoArgsConstructor
class PayloadSizeTest {

  private static void append(final List<Object> list, final Object item) {
    list.add(item);
  }

  @Test
  void textCountsItsUtf8Bytes() {
    assertThat(PayloadSize.bytesIn("abc")).isEqualTo(3);
    assertThat(PayloadSize.bytesIn("é")).isEqualTo(2);
    assertThat(PayloadSize.bytesIn("€")).isEqualTo(3);
    assertThat(PayloadSize.bytesIn("😀")).isEqualTo(4); // a surrogate pair
    assertThat(PayloadSize.bytesIn(new StringBuilder("hi"))).isEqualTo(2);
  }

  @Test
  void arraysBuffersAndOptionalsAreCounted() {
    assertThat(PayloadSize.bytesIn(new byte[5])).isEqualTo(5);
    assertThat(PayloadSize.bytesIn(new String[] {"ab", "cd"})).isEqualTo(4);
    assertThat(PayloadSize.bytesIn(ByteBuffer.allocate(7))).isEqualTo(7);
    assertThat(PayloadSize.bytesIn(Optional.of("abc"))).isEqualTo(3);
    assertThat(PayloadSize.bytesIn(Optional.empty())).isZero();
  }

  @Test
  void recordComponentsAreCounted() {
    record Body(String text, byte[] data, List<String> parts) {}

    assertThat(PayloadSize.bytesIn(new Body("abc", new byte[4], List.of("xy")))).isEqualTo(9);
  }

  @Test
  void pathIsNotWalked() {
    assertThat(PayloadSize.bytesIn(Path.of("a", "b", "c"))).isZero();
    assertThat(PayloadSize.bytesIn(List.of(Path.of("a", "b"), "xy"))).isEqualTo(2);
  }

  @Test
  void selfContainingContainerCountsOnce() {
    final List<Object> cyclic = new ArrayList<>();
    cyclic.add("abc");
    append(cyclic, cyclic);
    final Map<String, Object> map = new ConcurrentHashMap<>();
    map.put("k", map);

    final Object[] array = {"xy", null};
    array[1] = array;

    assertThat(PayloadSize.bytesIn(array)).isEqualTo(2);
    assertThat(PayloadSize.bytesIn(cyclic)).isEqualTo(3);
    assertThat(PayloadSize.bytesIn(map)).isEqualTo(1);
  }

  @Test
  void mapsCountKeysAndValues() {
    assertThat(PayloadSize.bytesIn(Map.of("key", "value"))).isEqualTo(8);
    assertThat(PayloadSize.bytesIn(12)).isZero();
    assertThat(PayloadSize.bytesIn(null)).isZero();
  }
}
