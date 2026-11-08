// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** How a supervisor delegates: the kinds, the modes, the timeout and the defaults. */
@NoArgsConstructor
class DelegationTest {

  @Test
  void aDelegationNeedsAModeAndAPositiveTimeout() {
    assertThat(Delegation.DEFAULT.mode()).isEqualTo(DelegationMode.ORDERED);
    assertThat(Delegation.DEFAULT.timeout()).isEqualTo(Delegation.DEFAULT_TIMEOUT);
    assertThatThrownBy(() -> new Delegation(DelegationMode.ALL, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Delegation(null, Duration.ofSeconds(1)))
        .isInstanceOf(NullPointerException.class);
    assertThat(DelegationKind.values())
        .containsExactly(DelegationKind.APPROVAL, DelegationKind.INPUT, DelegationKind.CONSULT);
    assertThat(DelegationMode.values()).hasSize(3);
  }

  @Test
  void aSupervisorAsksEachKindTheConfiguredWayElseOrdered() {
    final Delegation all = new Delegation(DelegationMode.ALL, Duration.ofSeconds(5));
    final Supervisor supervisor =
        new Supervisor(
            "child",
            "t",
            Map.of(),
            false,
            null,
            List.of("a", "b"),
            Map.of(DelegationKind.APPROVAL, all));

    assertThat(supervisor.parents()).containsExactly("a", "b");
    assertThat(supervisor.delegationFor(DelegationKind.APPROVAL)).isEqualTo(all);
    assertThat(supervisor.delegationFor(DelegationKind.INPUT)).isEqualTo(Delegation.DEFAULT);
    assertThat(new Supervisor("x", "t", Map.of(), false, null, null, null).parents()).isEmpty();
    assertThat(new Supervisor("x", "t", Map.of(), false, "d").delegation()).isEmpty();
  }
}
