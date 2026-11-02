// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.input.InputRisk;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DecisionTest {

  @Test
  void allowAndDenyKeepTheirTwoArgumentShape() {
    assertThat(Decision.ALLOW).isEqualTo(new Decision(true, null));
    assertThat(Decision.ALLOW.approval()).isNull();
    assertThat(Decision.ALLOW.requiresApproval()).isFalse();
    final Decision deny = Decision.deny("no");
    assertThat(deny.allowed()).isFalse();
    assertThat(deny.reason()).isEqualTo("no");
    assertThat(deny.requiresApproval()).isFalse();
  }

  @Test
  void requireApprovalIsNotAllowedAndCarriesTheNeed() {
    final ApprovalNeed need = new ApprovalNeed(InputRisk.MEDIUM, "ops", Duration.ofMinutes(5));
    final Decision decision = Decision.requireApproval("production stop", need);
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).isEqualTo("production stop");
    assertThat(decision.approval()).isEqualTo(need);
    assertThat(decision.requiresApproval()).isTrue();
  }

  @Test
  void requireApprovalNeedsTheNeed() {
    assertThatThrownBy(() -> Decision.requireApproval("x", null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void anAllowedDecisionCannotAskForApproval() {
    assertThatThrownBy(() -> new Decision(true, null, ApprovalNeed.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theNeedDefaultsToHighRiskTheDefaultRouteAndTheConfiguredTtl() {
    assertThat(ApprovalNeed.DEFAULT.risk()).isEqualTo(InputRisk.HIGH);
    assertThat(ApprovalNeed.DEFAULT.routeName()).isNull();
    assertThat(ApprovalNeed.DEFAULT.ttl()).isNull();
    assertThat(new ApprovalNeed(null, null, null).risk()).isEqualTo(InputRisk.HIGH);
  }

  @Test
  void theNeedRefusesBadRouteOrTtl() {
    assertThatThrownBy(() -> new ApprovalNeed(InputRisk.LOW, "bad route", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routeName");
    assertThatThrownBy(() -> new ApprovalNeed(InputRisk.LOW, null, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttl");
    assertThatThrownBy(() -> new ApprovalNeed(InputRisk.LOW, null, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
