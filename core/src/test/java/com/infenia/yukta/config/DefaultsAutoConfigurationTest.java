// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.SingleServerLeader;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.UnitTestShouldIncludeAssert"})
class DefaultsAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DefaultsAutoConfiguration.class))
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void withNothingElse_theSingleServerDefaultsAreRegistered() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(InMemoryExecutionLogStore.class);
          assertThat(context).hasSingleBean(InMemoryNodeCheckpointStore.class);
          assertThat(context).hasSingleBean(SingleServerLeader.class);
        });
  }

  @Test
  void anotherBeanOfTheSameType_replacesTheDefault() {
    runner
        .withBean("log", ExecutionLogStore.class, () -> Mockito.mock(ExecutionLogStore.class))
        .withBean("cp", NodeCheckpointStore.class, () -> Mockito.mock(NodeCheckpointStore.class))
        .withBean("le", LeaderElection.class, () -> Mockito.mock(LeaderElection.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(ExecutionLogStore.class);
              assertThat(context).hasSingleBean(NodeCheckpointStore.class);
              assertThat(context).hasSingleBean(LeaderElection.class);
              assertThat(context).doesNotHaveBean(InMemoryExecutionLogStore.class);
              assertThat(context).doesNotHaveBean(InMemoryNodeCheckpointStore.class);
              assertThat(context).doesNotHaveBean(SingleServerLeader.class);
            });
  }

  @Test
  void theStoreSettingsStillSwitchTheDefaultsOff() {
    runner
        .withPropertyValues(
            "yukta.execution-log.dir=/tmp/l", "yukta.restart.dir=/tmp/r", "yukta.ha.mode=postgres")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(InMemoryExecutionLogStore.class);
              assertThat(context).doesNotHaveBean(InMemoryNodeCheckpointStore.class);
              assertThat(context).doesNotHaveBean(SingleServerLeader.class);
            });
  }
}
