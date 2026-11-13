// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/** The beans the module contributes for AGENT_SESSION. */
@NoArgsConstructor
class ProcessExecutorAutoConfigurationTest {

  @Test
  @SuppressWarnings("unchecked")
  void theAgentBeansAreWiredTogether() throws IOException {
    final ProcessExecutorAutoConfiguration config = new ProcessExecutorAutoConfiguration();
    final ObjectMapper json = new ObjectMapper();
    final AgentHarnesses harnesses = config.agentHarnesses(json, "");
    final AgentSessionStore sessions = config.agentSessionStore();

    final AgentSessionPlugin plugin =
        config.agentSessionPlugin(
            new ProcessExecutorGateway(),
            mock(VariableResolver.class),
            harnesses,
            sessions,
            mock(ObjectProvider.class),
            json);

    assertThat(harnesses.get("codex")).isNotNull();
    assertThat(sessions).isInstanceOf(InMemoryAgentSessionStore.class);
    assertThat(plugin.getType()).isEqualTo("AGENT_SESSION");
  }
}
