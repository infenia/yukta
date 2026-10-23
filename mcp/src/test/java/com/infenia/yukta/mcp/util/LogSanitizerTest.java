// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
  void longLineIsCutAndMarked() {
    assertThat(new LogSanitizer(null, 3).clean("abcdef")).isEqualTo("abc…[cut]");
  }
}
