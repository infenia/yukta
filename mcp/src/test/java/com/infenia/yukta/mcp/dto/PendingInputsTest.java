// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class PendingInputsTest {

  private static PendingInputs.PendingInput entry(final List<String> options) {
    return new PendingInputs.PendingInput(
        "s",
        "w",
        "e",
        "r",
        "n",
        "OPEN",
        0,
        "CHOICE",
        "LOW",
        "Which?",
        "{}",
        options,
        null,
        "default",
        null,
        null,
        Instant.EPOCH,
        null);
  }

  @Test
  void missingListsAreEmpty() {
    final PendingInputs inputs = new PendingInputs(null, null, null, false);

    assertThat(inputs.states()).isEmpty();
    assertThat(inputs.inputs()).isEmpty();
    assertThat(entry(null).options()).isEmpty();
  }

  @Test
  void listsAreCopiesThatCannotChange() {
    final List<String> options = new ArrayList<>(List.of("a", "b"));
    final PendingInputs.PendingInput entry = entry(options);
    options.add("c");
    final PendingInputs inputs = new PendingInputs("s", List.of("OPEN"), List.of(entry), true);

    assertThat(entry.options()).containsExactly("a", "b");
    assertThatThrownBy(() -> inputs.inputs().add(entry))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(inputs.truncated()).isTrue();
  }
}
