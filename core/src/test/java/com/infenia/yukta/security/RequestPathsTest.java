// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the behaviour described by {@link RequestPaths}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RequestPathsTest {

  @Test
  void ambiguous_flagsOtherSpellings() {
    for (final String path :
        new String[] {
          "/api;x=1/a",
          "/api\\a",
          "/api//a",
          "/api/./a",
          "/api/../a",
          "/api/a/..",
          "/api/a/.",
          "/api/%2e%2E/a",
          "/api/%2Fa",
          "/api/%5ca",
          "/api/%25",
          "/api/%zz"
        }) {
      assertThat(RequestPaths.ambiguous(path)).as(path).isTrue();
    }
  }

  @Test
  void ambiguous_allowsPlainPathsAndPlainEscapes() {
    assertThat(RequestPaths.ambiguous("/api/sessions/my%20s/workflows")).isFalse();
    assertThat(RequestPaths.ambiguous("/api/a.b/c")).isFalse();
    assertThat(RequestPaths.ambiguous("/")).isFalse();
  }

  @Test
  void decoded_decodesEscapes() {
    assertThat(RequestPaths.decoded("/%61pi/my%20s")).isEqualTo("/api/my s");
  }

  @Test
  void normalized_dropsParametersDotSegmentsAndTrailingSlash() {
    assertThat(RequestPaths.normalized("/mcp;jsessionid=1")).isEqualTo("/mcp");
    assertThat(RequestPaths.normalized("/x/../%6dcp")).isEqualTo("/mcp");
    assertThat(RequestPaths.normalized("\\mcp")).isEqualTo("/mcp");
    assertThat(RequestPaths.normalized("/mcp/")).isEqualTo("/mcp");
    assertThat(RequestPaths.normalized("/")).isEqualTo("/");
    assertThat(RequestPaths.normalized("/%zz")).isEqualTo("/%zz");
  }
}
