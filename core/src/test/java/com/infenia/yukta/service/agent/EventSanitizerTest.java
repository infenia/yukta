// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Text is cut by bytes, never in the middle of a character. */
@NoArgsConstructor
class EventSanitizerTest {

  @Test
  void aTextThatFitsIsReturnedAsIs() {
    final EventSanitizer.Limited limited = EventSanitizer.limit("hello", 5);

    assertThat(limited.value()).isEqualTo("hello");
    assertThat(limited.cut()).isFalse();
  }

  @Test
  void aTextOverTheLimitIsCutAndFlagged() {
    final EventSanitizer.Limited limited = EventSanitizer.limit("hello world", 5);

    assertThat(limited.value()).isEqualTo("hello");
    assertThat(limited.cut()).isTrue();
  }

  @Test
  void aCharacterThatDoesNotFitIsDroppedWhole() {
    assertThat(EventSanitizer.limit("aé", 2).value()).isEqualTo("a");
    assertThat(EventSanitizer.limit("😀😀", 5).value()).isEqualTo("😀");
    assertThat(EventSanitizer.limit("😀", 3).value()).isEqualTo("");
  }

  @Test
  void multiByteTextThatFitsInBytesIsNotCut() {
    assertThat(EventSanitizer.limit("éé", 4).cut()).isFalse();
  }
}
