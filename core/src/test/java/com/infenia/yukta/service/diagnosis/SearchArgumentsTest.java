// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.plugin.core.LogLevel;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for SearchArguments. */
@NoArgsConstructor
class SearchArgumentsTest {

  @Test
  void stream_readsEveryNameInAnyCase() {
    assertThat(SearchArguments.stream(null)).isNull();
    assertThat(SearchArguments.stream(" All ")).isNull();
    assertThat(SearchArguments.stream("")).isNull();
    assertThat(SearchArguments.stream("STDOUT")).isEqualTo(LogStream.STDOUT);
    assertThat(SearchArguments.stream("stderr")).isEqualTo(LogStream.STDERR);
    assertThatThrownBy(() -> SearchArguments.stream("both"))
        .hasMessage("stream must be stdout, stderr or all");
  }

  @Test
  void level_readsEveryNameInAnyCase() {
    assertThat(SearchArguments.level(null)).isNull();
    assertThat(SearchArguments.level(" ")).isNull();
    assertThat(SearchArguments.level(" warn")).isEqualTo(LogLevel.WARN);
    assertThatThrownBy(() -> SearchArguments.level("loud"))
        .hasMessage("level must be ERROR, WARN, INFO or DEBUG");
  }

  @Test
  void nodes_dropBlanks() {
    assertThat(SearchArguments.nodes(null)).isEmpty();
    assertThat(SearchArguments.nodes("a, ,b,")).isEqualTo(Set.of("a", "b"));
  }
}
