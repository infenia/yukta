// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.SupervisorContract;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** The contract endpoint. */
@NoArgsConstructor
class SupervisorContractControllerTest {

  @Test
  void returnsTheVersionAndOperations() {
    StepVerifier.create(new SupervisorContractController().get())
        .assertNext(
            r -> {
              assertThat(r.data().version()).isEqualTo(1);
              assertThat(r.data().operations()).isEqualTo(SupervisorContract.operations());
              assertThat(r.data().operations().get("act")).contains("run.pause", "run.start");
            })
        .verifyComplete();
  }
}
