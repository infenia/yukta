// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.agent.AgentConversationGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The beans the module contributes for AGENT_SESSION. */
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class AgentSessionAutoConfigurationTest {

  @Test
  @SuppressWarnings("unchecked")
  void theAgentBeansAreWiredTogether() throws IOException {
    final AgentSessionAutoConfiguration config = new AgentSessionAutoConfiguration();
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
            mock(ObjectProvider.class),
            json,
            config.ptySettings(false, "yukta-cli", 120, 40));

    assertThat(harnesses.get("codex")).isNotNull();
    assertThat(sessions).isInstanceOf(InMemoryAgentSessionStore.class);
    assertThat(plugin.getType()).isEqualTo("AGENT_SESSION");
  }

  @Test
  void theContextBuildsThePluginWithOrWithoutTheConversationGateway() {
    final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentSessionAutoConfiguration.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean(ProcessExecutorGateway.class, ProcessExecutorGateway::new)
            .withBean(VariableResolver.class, () -> mock(VariableResolver.class));

    runner.run(context -> {
      assertThat(context).hasSingleBean(AgentSessionPlugin.class);
      assertThat(context).hasSingleBean(AgentHarnesses.class);
      assertThat(context).hasSingleBean(AgentSessionStore.class);
    });
    runner
        .withBean(AgentConversationGateway.class, () -> mock(AgentConversationGateway.class))
        .run(context -> assertThat(context).hasSingleBean(AgentSessionPlugin.class));
  }
}
