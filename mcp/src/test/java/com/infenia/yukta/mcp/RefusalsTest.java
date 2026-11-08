// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ControlledBySupervisorException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RefusalsTest {

  @Test
  void aSupervisorsRefusalNamesTheCodeTheSupervisorAndTheReason() {
    StepVerifier.create(
            Refusals.named(
                Mono.<String>error(
                    new ControlledBySupervisorException("guard", "guard controls this"))))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CONTROLLED_BY_SUPERVISOR (supervisor guard)")
                    .hasMessageContaining("guard controls this")
                    .hasMessageContaining("not an error to retry"))
        .verify();
  }

  @Test
  void aRefusalWithNoOwnerAndAPlainDenialAreNamedToo() {
    StepVerifier.create(
            Refusals.named(Mono.<String>error(new ControlledBySupervisorException(null, "nobody"))))
        .expectErrorSatisfies(
            error -> assertThat(error).hasMessageStartingWith("CONTROLLED_BY_SUPERVISOR: nobody"))
        .verify();
    StepVerifier.create(
            Refusals.named(Mono.<String>error(new ControlRejectedException("not on friday"))))
        .expectErrorSatisfies(
            error -> assertThat(error).hasMessageContaining("CONTROL_REJECTED: not on friday"))
        .verify();
    StepVerifier.create(Refusals.named(Mono.just("fine"))).expectNext("fine").verifyComplete();
    StepVerifier.create(Refusals.named(Mono.<String>error(new IllegalStateException("other"))))
        .expectError(IllegalStateException.class)
        .verify();
  }
}
