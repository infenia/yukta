// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SmallTypesTest {

  @Test
  void riskIsOrdered() {
    assertThat(InputRisk.HIGH.atLeast(InputRisk.MEDIUM)).isTrue();
    assertThat(InputRisk.MEDIUM.atLeast(InputRisk.MEDIUM)).isTrue();
    assertThat(InputRisk.LOW.atLeast(InputRisk.MEDIUM)).isFalse();
  }

  @Test
  void onlyThreeStatesAreFinal() {
    assertThat(InputState.OPEN.isTerminal()).isFalse();
    assertThat(InputState.ESCALATED.isTerminal()).isFalse();
    assertThat(InputState.ANSWERED.isTerminal()).isTrue();
    assertThat(InputState.EXPIRED.isTerminal()).isTrue();
    assertThat(InputState.CANCELLED.isTerminal()).isTrue();
  }

  @Test
  void anOptionFillsInItsLabel() {
    assertThat(new InputOption("approve").label()).isEqualTo("approve");
    assertThat(new InputOption("approve", " ").label()).isEqualTo("approve");
    assertThat(new InputOption("approve", "Go ahead").label()).isEqualTo("Go ahead");
  }

  @Test
  void anOptionNeedsAnId() {
    assertThatThrownBy(() -> new InputOption(" ")).hasMessage("option id must not be blank");
    assertThatThrownBy(() -> new InputOption("a", "x".repeat(300)))
        .hasMessageContaining("option label is longer than");
  }

  @Test
  void responderNeedsTypeAndId() {
    assertThat(Responder.SYSTEM.type()).isEqualTo(Responder.Type.SYSTEM);
    assertThat(new Responder(Responder.Type.HUMAN, "key:ops").id()).isEqualTo("key:ops");
    assertThatThrownBy(() -> new Responder(null, "x")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Responder(Responder.Type.HUMAN, ""))
        .hasMessage("responder id must not be blank");
  }

  @Test
  void exceptionsCarryTheRequestId() {
    final InputException expired = new InputExpiredException("r1", "no answer");
    assertThat(expired.requestId()).isEqualTo("r1");
    assertThat(expired).hasMessage("no answer");
    assertThat(new InputCancelledException("r2", "run ended").requestId()).isEqualTo("r2");
    assertThat(new InputRejectedException("r3", "no").requestId()).isEqualTo("r3");
    assertThat(new InputLimitException("k", "too many").requestId()).isEqualTo("k");
  }
}
