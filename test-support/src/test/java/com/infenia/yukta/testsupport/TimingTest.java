// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class TimingTest {

  @Test
  void nextMillisecond_returnsOnceTheClockHasMovedOn() {
    final long before = System.currentTimeMillis();

    Timing.nextMillisecond();

    assertThat(System.currentTimeMillis()).isGreaterThan(before);
  }
}
