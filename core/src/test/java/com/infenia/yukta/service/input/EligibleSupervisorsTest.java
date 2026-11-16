// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class EligibleSupervisorsTest {

  @Test
  void noSupervisorsHaveNoParentsEither() {
    assertThat(EligibleSupervisors.NONE.parentsOf("any")).isEmpty();
    assertThat(EligibleSupervisors.NONE.bound()).isEmpty();
  }
}
