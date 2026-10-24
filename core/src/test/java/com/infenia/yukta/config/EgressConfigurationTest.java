// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.util.EgressPolicy;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.util.context.Context;

/** Tests for {@link EgressConfiguration}. */
@NoArgsConstructor
class EgressConfigurationTest {

  @Test
  void egressPolicy_readsTheDeploymentWorkflowAndSessionLists() {
    final MockEnvironment env =
        new MockEnvironment()
            .withProperty("yukta.egress.allowed-hosts", "a.example.com,b.example.com")
            .withProperty("yukta.egress.by-workflow.wf", "a.example.com")
            .withProperty("yukta.egress.by-session.s1", "b.example.com");

    final EgressPolicy policy = new EgressConfiguration().egressPolicy(env);

    assertThat(policy.refusalFor("https://evil.org/", Context.empty())).isNotNull();
    assertThat(policy.refusalFor("https://b.example.com/", Context.empty()))
        .contains("no workflowId or sessionId");
    assertThat(policy.refusalFor("https://b.example.com/", Context.of("workflowId", "wf")))
        .contains("yukta.egress.by-workflow.wf");
    assertThat(policy.refusalFor("https://a.example.com/", Context.of("sessionId", "s1")))
        .contains("yukta.egress.by-session.s1");
  }

  @Test
  void egressPolicy_withNothingSetAllowsEverything() {
    final EgressPolicy policy = new EgressConfiguration().egressPolicy(new MockEnvironment());

    assertThat(policy.refusalFor("https://anywhere.org/", Context.empty())).isNull();
  }
}
