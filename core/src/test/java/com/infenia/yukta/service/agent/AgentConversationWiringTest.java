// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.agent.AgentConversationGateway;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.startup.DefaultsAutoConfiguration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The conversation beans exist by default and not at all when agents are switched off. */
@NoArgsConstructor
@SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
class AgentConversationWiringTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DefaultsAutoConfiguration.class))
          .withUserConfiguration(ScanAgentPackage.class)
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
          .withBean(InputRedactor.class, () -> new InputRedactor(List.of()))
          .withBean(TaskTrackerService.class, () -> mock(TaskTrackerService.class));

  @Test
  void byDefaultTheStoreTheServiceTheGatewayAndTheRetentionExist() {
    runner.run(context -> {
      assertThat(context).hasSingleBean(AgentConversationStore.class);
      assertThat(context.getBean(AgentConversationStore.class))
          .isInstanceOf(InMemoryAgentConversationStore.class);
      assertThat(context).hasSingleBean(AgentConversationService.class);
      assertThat(context).hasSingleBean(AgentConversationGateway.class);
      assertThat(context.getBean(AgentConversationGateway.class))
          .isInstanceOf(DefaultAgentConversationGateway.class);
      assertThat(context).hasSingleBean(AgentRetention.class);
    });
  }

  @Test
  void theBoundsOfTheDefaultStoreComeFromTheSettings() {
    runner
        .withPropertyValues("yukta.agents.max-conversations=3")
        .run(context -> assertThat(context).hasSingleBean(InMemoryAgentConversationStore.class));
  }

  @Test
  void withAgentsDisabledNoGatewayAndNothingElseExists() {
    runner
        .withPropertyValues("yukta.agents.enabled=false")
        .run(context -> {
          assertThat(context).doesNotHaveBean(AgentConversationGateway.class);
          assertThat(context).doesNotHaveBean(AgentConversationService.class);
          assertThat(context).doesNotHaveBean(AgentRetention.class);
          assertThat(context).doesNotHaveBean(AgentConversationStore.class);
        });
  }

  @Test
  void storeOfYourOwnReplacesTheInMemoryOne() {
    final AgentConversationStore own = mock(AgentConversationStore.class);

    runner
        .withBean(AgentConversationStore.class, () -> own)
        .run(context -> {
          assertThat(context).hasSingleBean(AgentConversationStore.class);
          assertThat(context.getBean(AgentConversationStore.class)).isSameAs(own);
        });
  }

  /** Scans the conversation package the way the application does. */
  @Configuration
  @ComponentScan(basePackageClasses = AgentConversationService.class)
  /* default */ static class ScanAgentPackage {}
}
