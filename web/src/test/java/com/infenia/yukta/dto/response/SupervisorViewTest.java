// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SupervisorViewTest {

  @Test
  void missingParentsAndDelegationBecomeEmptyAndGivenOnesAreCopied() {
    final SupervisorView none =
        new SupervisorView("s", "t", "d", Map.of(), false, false, 0, 0, 0, 0, 0, null, null);
    assertThat(none.parents()).isEmpty();
    assertThat(none.delegation()).isEmpty();

    final SupervisorView some =
        new SupervisorView(
            "s",
            "t",
            "d",
            Map.of("k", "v"),
            false,
            false,
            0,
            0,
            0,
            0,
            0,
            List.of("boss"),
            Map.of("APPROVAL", new SupervisorView.DelegationView("ALL", 5)));
    assertThat(some.parents()).containsExactly("boss");
    assertThat(some.delegation()).containsOnlyKeys("APPROVAL");
    assertThat(some.settings()).containsEntry("k", "v");
  }
}
