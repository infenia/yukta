// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.time.Duration;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.ObjectMapper;

/** Tests for {@link AppConfiguration}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class AppConfigurationTest {

  /** Application context runner for testing Spring configuration. */
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AppConfiguration.class));

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldProvideDefaultBeans() {
    this.contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(ObjectMapper.class);
          assertThat(context).hasSingleBean(Scheduler.class);
          assertThat(context).hasSingleBean(Duration.class);
          assertThat(context).hasBean("heartbeatInterval");
          assertThat(context.getBean("heartbeatInterval")).isEqualTo(Duration.ofSeconds(10));
          assertThat(context).hasSingleBean(ExecutionControlStore.class);
          assertThat(context.getBean(ExecutionControlStore.class))
              .isInstanceOf(InMemoryExecutionControlStore.class);
        });
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldNotOverrideExistingObjectMapper() {
    this.contextRunner
        .withUserConfiguration(CustomObjectMapperConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(ObjectMapper.class);
              assertThat(context.getBean(ObjectMapper.class))
                  .isSameAs(context.getBean("customObjectMapper"));
            });
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldNotOverrideExistingSessionConfigStore() {
    final SessionConfigStore customStore = mock(SessionConfigStore.class);
    this.contextRunner
        .withBean(SessionConfigStore.class, () -> customStore)
        .run(
            context -> {
              assertThat(context).hasSingleBean(SessionConfigStore.class);
              assertThat(context.getBean(SessionConfigStore.class)).isSameAs(customStore);
            });
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldNotOverrideExistingExecutionControlStore() {
    final ExecutionControlStore customStore = mock(ExecutionControlStore.class);
    this.contextRunner
        .withBean(ExecutionControlStore.class, () -> customStore)
        .run(
            context -> {
              assertThat(context).hasSingleBean(ExecutionControlStore.class);
              assertThat(context.getBean(ExecutionControlStore.class)).isSameAs(customStore);
            });
  }

  @Test
  void shouldBeInstantiable() {
    final AppConfiguration config = new AppConfiguration();
    assertThat(config).isNotNull();
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void shouldRegisterSessionConfigProperties() {
    this.contextRunner.run(
        context -> assertThat(context).hasSingleBean(SessionConfigProperties.class));
  }

  /** Custom object mapper configuration for testing. */
  @Configuration
  /* package */ static class CustomObjectMapperConfiguration {
    /**
     * Creates a custom object mapper.
     *
     * @return the object mapper
     */
    @Bean
    /* package */ ObjectMapper customObjectMapper() {
      return new ObjectMapper();
    }
  }
}
