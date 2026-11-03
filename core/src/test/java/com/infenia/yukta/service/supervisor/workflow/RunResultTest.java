// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RunResultTest {

  @Test
  void noneHasNothing() {
    final RunResult none = RunResult.none("FAILED");
    assertThat(none.status()).isEqualTo("FAILED");
    assertThat(none.outcome()).isNull();
    assertThat(new RunResult("SUCCESS", "SUCCESS", null, List.of(1)).results()).containsExactly(1);
  }
}
