// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.supervisor.SupervisorHost;
import com.infenia.yukta.service.supervisor.SupervisorInfo;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for {@link SupervisorsController}. */
@NoArgsConstructor
class SupervisorsControllerTest {

  @Test
  void list_wrapsTheStatusOfTheHost() {
    final SupervisorHost host = mock(SupervisorHost.class);
    final SupervisorInfo info =
        new SupervisorInfo("rules", "SUPERVISOR", "RuleSupervisor", 0, false, 20, 3, 1, 0, 0);
    when(host.status()).thenReturn(List.of(info));

    StepVerifier.create(new SupervisorsController(host).list())
        .assertNext(
            response -> {
              org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo(200);
              org.assertj.core.api.Assertions.assertThat(response.data()).containsExactly(info);
            })
        .verifyComplete();
  }
}
