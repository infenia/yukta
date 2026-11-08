// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.SupervisorService;
import com.infenia.yukta.service.supervisor.agent.AgentCredentials;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class AgentCredentialControllerTest {

  private final SupervisorService supervisors = mock(SupervisorService.class);
  private final AgentCredentials credentials = new AgentCredentials();
  private final AgentCredentialController controller =
      new AgentCredentialController(supervisors, credentials);

  private void has(final String id, final String type) {
    when(supervisors.supervisors())
        .thenReturn(Flux.just(new Supervisor(id, type, Map.of(), false)));
  }

  @Test
  void anHttpSupervisorGetsACredentialThatWorksUntilRevoked() {
    has("a1", "http");
    final String[] token = new String[1];
    StepVerifier.create(controller.issue("a1", 2, null))
        .assertNext(
            r -> {
              assertThat(r.status()).isEqualTo(201);
              assertThat(r.data().expiresAt()).isNotNull();
              token[0] = r.data().token();
            })
        .verifyComplete();
    assertThat(credentials.authenticate(token[0])).contains("a1");

    assertThat(controller.revoke("a1").block().status()).isEqualTo(200);
    assertThat(credentials.authenticate(token[0])).isEmpty();
    assertThatThrownBy(() -> controller.revoke("a1")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void onlyAnHttpSupervisorMayHaveOneAndTheLifetimeIsPositive() {
    has("r1", "rule-supervisor");
    StepVerifier.create(controller.issue("r1", null, null))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(controller.issue("nope", null, null))
        .expectError(NotFoundException.class)
        .verify();
    assertThatThrownBy(() -> controller.issue("r1", 0, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
