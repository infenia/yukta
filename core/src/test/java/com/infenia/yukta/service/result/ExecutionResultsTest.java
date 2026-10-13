// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class ExecutionResultsTest {

  @Test
  void missingEventsBecomeAnEmptyList() {
    assertThat(new ExecutionResults("e1", false, null, false, 0, null).events()).isEmpty();
  }

  @Test
  void eventsAreCopied() {
    final List<ResultEvent> source = new ArrayList<>();
    final ExecutionResults results = new ExecutionResults("e1", false, null, false, 0, source);
    source.add(
        new ResultEvent(
            ResultEvent.Kind.RESULT, "t", 0, "x", null, false, java.time.Instant.now()));

    assertThat(results.events()).isEqualTo(List.of());
  }
}
