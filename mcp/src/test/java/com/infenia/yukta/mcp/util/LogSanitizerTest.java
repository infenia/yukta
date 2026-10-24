// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LogSanitizerTest {

  @Test
  void nullPatternsUseTheDefaults() {
    assertThat(new LogSanitizer(null, 0).clean("api_key=abc123")).isEqualTo("[REDACTED]");
  }

  @Test
  void defaultsCoverCommonKeyShapes() {
    final LogSanitizer sanitizer = new LogSanitizer(List.of(), 0);

    assertThat(sanitizer.clean("k sk-abcdefghijklmnopqrstu")).isEqualTo("k [REDACTED]");
    assertThat(sanitizer.clean("k ghp_abcdefghijklmnopqrstuv")).isEqualTo("k [REDACTED]");
    assertThat(sanitizer.clean("k AKIAABCDEFGHIJKLMNOP")).isEqualTo("k [REDACTED]");
  }

  @Test
  void lineBreaksBecomeSpacesAndZeroMeansNoCut() {
    assertThat(new LogSanitizer(null, 0).clean("a\r\nb" + "x".repeat(5000)))
        .startsWith("a b")
        .hasSize(5003);
  }

  @Test
  void redactKeepsOtherValuesAndStopsAtAbsurdDepth() {
    final LogSanitizer sanitizer = new LogSanitizer(null, 0);
    final Map<String, Object> withNullKey = new HashMap<>();
    withNullKey.put(null, "password=x");
    Object deep = "bottom";
    for (int i = 0; i < 40; i++) {
      deep = new ArrayList<>(List.of(deep));
    }

    assertThat(sanitizer.redact(null)).isNull();
    assertThat(sanitizer.redact(7)).isEqualTo(7);
    assertThat(((Map<?, ?>) sanitizer.redact(withNullKey)).get(null)).isEqualTo("[REDACTED]");
    assertThat(sanitizer.redact(deep).toString()).contains("[REDACTED]").doesNotContain("bottom");
  }

  @Test
  void longLineIsCutAndMarked() {
    assertThat(new LogSanitizer(null, 3).clean("abcdef")).isEqualTo("abc…[cut]");
  }
}
