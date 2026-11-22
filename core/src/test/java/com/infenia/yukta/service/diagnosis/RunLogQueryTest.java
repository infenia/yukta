// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for RunLogQuery. */
@NoArgsConstructor
class RunLogQueryTest {

  @Test
  void defaults_areFilledIn() {
    final RunLogQuery query = RunLogQuery.builder().executionId("e").grep("").build();

    assertThat(query.mode()).isEqualTo(LogSearchMode.COUNT);
    assertThat(query.nodes()).isEmpty();
    assertThat(query.grep()).isNull();
    assertThat(query.maxMatches()).isEqualTo(RunLogQuery.DEFAULT_MATCHES);
    assertThat(query.maxBytes()).isEqualTo(RunLogQuery.DEFAULT_BYTES);
  }

  @Test
  void budgets_areCapped() {
    final RunLogQuery query =
        RunLogQuery.builder()
            .executionId("e")
            .context(99)
            .maxMatches(10_000)
            .maxBytes(10_000_000)
            .build();

    assertThat(query.context()).isEqualTo(20);
    assertThat(query.maxMatches()).isEqualTo(RunLogQuery.MAX_MATCHES);
    assertThat(query.maxBytes()).isEqualTo(RunLogQuery.MAX_BYTES);
  }

  @Test
  void badArguments_areRefused() {
    assertThatThrownBy(() -> RunLogQuery.builder().build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunLogQuery.builder().executionId(" ").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunLogQuery.builder().executionId("e").context(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunLogQuery.builder().executionId("e").maxMatches(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunLogQuery.builder().executionId("e").maxBytes(-1).build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
