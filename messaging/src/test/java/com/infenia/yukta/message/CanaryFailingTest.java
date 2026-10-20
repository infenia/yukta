// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package com.infenia.yukta.message;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanaryFailingTest {

  @Test
  void failsOnPurpose() {
    assertThat(new CanarySpotless().value()).isEqualTo(2);
  }
}
