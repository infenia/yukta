// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.edition.Edition;
import com.infenia.yukta.edition.EditionInfo;
import java.util.List;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link EditionController}. */
@NoArgsConstructor
class EditionControllerTest {

  @Test
  void show_returnsTheEditionOfTheBuild() {
    final EditionInfo plus =
        new EditionInfo(Edition.PLUS, "AGPL-3.0-only", List.of("file-stores"), "https://x");

    final var data = Objects.requireNonNull(new EditionController(plus).show().block()).data();

    assertThat(data).isEqualTo(plus);
    assertThat(
            Objects.requireNonNull(new EditionController(EditionInfo.community()).show().block())
                .data()
                .edition())
        .isEqualTo(Edition.COMMUNITY);
  }
}
