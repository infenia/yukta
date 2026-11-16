// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.service.redact.InputRedactor;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Text is cut by bytes, never in the middle of a character. */
@NoArgsConstructor
class EventSanitizerTest {

  @Test
  void textThatFitsIsReturnedAsIs() {
    final EventSanitizer.Limited limited = EventSanitizer.limit("hello", 5);

    assertThat(limited.value()).isEqualTo("hello");
    assertThat(limited.cut()).isFalse();
  }

  @Test
  void textOverTheLimitIsCutAndFlagged() {
    final EventSanitizer.Limited limited = EventSanitizer.limit("hello world", 5);

    assertThat(limited.value()).isEqualTo("hello");
    assertThat(limited.cut()).isTrue();
  }

  @Test
  void characterThatDoesNotFitIsDroppedWhole() {
    assertThat(EventSanitizer.limit("aé", 2).value()).isEqualTo("a");
    assertThat(EventSanitizer.limit("😀😀", 5).value()).isEqualTo("😀");
    assertThat(EventSanitizer.limit("😀", 3).value()).isEqualTo("");
  }

  @Test
  void multiByteTextThatFitsInBytesIsNotCut() {
    assertThat(EventSanitizer.limit("éé", 4).cut()).isFalse();
  }

  @Test
  void planEntriesAreRedactedAndACutEntryIsFlagged() {
    final EventSanitizer sanitizer =
        new EventSanitizer(new InputRedactor(List.of()), JsonMapper.builder().build(), 1000, 100);

    final Map<String, Object> plan =
        sanitizer
            .sanitize(
                AgentEventKind.PLAN,
                Map.of("entries", List.of("${env.TOP_SECRET}", "a".repeat(200))))
            .getFirst();

    assertThat(plan.get(EventSanitizer.TRUNCATED)).isEqualTo(true);
    assertThat(plan.get("entries").toString()).doesNotContain("TOP_SECRET");
  }
}
