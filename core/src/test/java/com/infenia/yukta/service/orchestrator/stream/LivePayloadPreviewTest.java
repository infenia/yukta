// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link LivePayloadPreview}. */
@NoArgsConstructor
class LivePayloadPreviewTest {

  /** Mirrors the limit of the preview, in characters. */
  private static final int LIMIT = 2_048;

  /** Mirrors the element count above which a collection is described. */
  private static final int ELEMENTS = 256;

  @Test
  void shortTextIsEchoedAsIs() {
    assertThat(LivePayloadPreview.preview("hello")).isEqualTo("hello");
    assertThat(LivePayloadPreview.preview(CharBuffer.wrap("sb"))).isEqualTo("sb");
  }

  @Test
  void textOfExactlyTheLimitIsNotCut() {
    final String text = "x".repeat(LIMIT);

    assertThat(LivePayloadPreview.preview(text)).isEqualTo(text);
  }

  @Test
  void longTextGetsTruncationMarker() {
    final String text = "0123456789".repeat(300);

    final String preview = LivePayloadPreview.preview(text);

    assertThat(preview).isEqualTo(text.substring(0, LIMIT) + "... [truncated 952 chars]");
  }

  @Test
  void longCharSequenceIsCutLikeText() {
    final CharBuffer builder = CharBuffer.wrap("a".repeat(LIMIT + 10));

    assertThat(LivePayloadPreview.preview(builder))
        .isEqualTo("a".repeat(LIMIT) + "... [truncated 10 chars]");
  }

  @Test
  void binaryPayloadIsDescribedByItsSize() {
    assertThat(LivePayloadPreview.preview(new byte[5_000_000])).isEqualTo("<5000000 bytes>");
    assertThat(LivePayloadPreview.preview(new byte[0])).isEqualTo("<0 bytes>");
  }

  @Test
  void smallCollectionIsPrintedAndLargeOneIsDescribed() {
    assertThat(LivePayloadPreview.preview(List.of(1, 2))).isEqualTo("[1, 2]");

    final List<String> large = new ArrayList<>(Collections.nCopies(ELEMENTS + 1, "x"));

    assertThat(LivePayloadPreview.preview(large))
        .isEqualTo("ArrayList of " + (ELEMENTS + 1) + " elements");
  }

  @Test
  void smallMapIsPrintedAndLargeOneIsDescribed() {
    assertThat(LivePayloadPreview.preview(Map.of("a", 1))).isEqualTo("{a=1}");

    final var large = new ConcurrentHashMap<>();
    for (int i = 0; i <= ELEMENTS; i++) {
      large.put(i, i);
    }

    assertThat(LivePayloadPreview.preview(large))
        .isEqualTo("ConcurrentHashMap of " + (ELEMENTS + 1) + " entries");
  }

  @Test
  void nullPayloadIsNamed() {
    assertThat(LivePayloadPreview.preview(null)).isEqualTo("null");
  }

  @Test
  void otherPayloadIsPrintedAndCutWhenItsTextIsLong() {
    assertThat(LivePayloadPreview.preview(new Wide("ok"))).isEqualTo("ok");

    assertThat(LivePayloadPreview.preview(new Wide("y".repeat(LIMIT + 5))))
        .isEqualTo("y".repeat(LIMIT) + "... [truncated 5 chars]");
  }

  /** A payload whose text is given, so its printed form is known. */
  private record Wide(String text) {

    @Override
    public String toString() {
      return text;
    }
  }
}
