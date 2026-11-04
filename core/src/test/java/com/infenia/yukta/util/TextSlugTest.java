// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.util;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class TextSlugTest {

  // [T1]
  @Test
  void slug_mixedPunctuationAndCase_returnsLowerCaseHyphenated() {
    assertThat(TextSlug.toSlug("Hello, World!")).isEqualTo("hello-world");
  }

  // [T2]
  @Test
  void slug_separatorRunsAndEdges_collapsesRunsAndTrimsEdges() {
    assertThat(TextSlug.toSlug("  --Foo   &&  Bar__1!! ")).isEqualTo("foo-bar-1");
  }

  // [T3]
  @Test
  void slug_nullOrEmpty_returnsEmptyString() {
    assertThat(TextSlug.toSlug(null)).isEmpty();
    assertThat(TextSlug.toSlug("")).isEmpty();
  }

  // [T4]
  @Test
  void slug_noAlphanumerics_returnsEmptyString() {
    assertThat(TextSlug.toSlug("!?- ")).isEmpty();
  }
}
