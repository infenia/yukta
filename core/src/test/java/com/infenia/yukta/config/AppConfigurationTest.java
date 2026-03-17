/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.session.SessionConfigStore;
import com.infenia.yukta.service.session.SessionConfigStoreFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.ObjectMapper;

class AppConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AppConfiguration.class));

  @Test
  void shouldProvideDefaultBeans() {
    this.contextRunner
        .withBean(SessionConfigStoreFactory.class, () -> mock(SessionConfigStoreFactory.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(ObjectMapper.class);
              assertThat(context).hasSingleBean(Scheduler.class);
              assertThat(context).hasSingleBean(Duration.class);
              assertThat(context).hasBean("heartbeatInterval");
              assertThat(context.getBean("heartbeatInterval")).isEqualTo(Duration.ofSeconds(10));
              assertThat(context).hasSingleBean(SessionConfigStore.class);
            });
  }

  @Test
  void shouldNotOverrideExistingObjectMapper() {
    ObjectMapper customMapper = new ObjectMapper();
    this.contextRunner
        .withUserConfiguration(CustomObjectMapperConfiguration.class)
        .withBean(SessionConfigStoreFactory.class, () -> mock(SessionConfigStoreFactory.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(ObjectMapper.class);
              assertThat(context.getBean(ObjectMapper.class))
                  .isSameAs(context.getBean("customObjectMapper"));
            });
  }

  @Test
  void shouldNotOverrideExistingSessionConfigStore() {
    SessionConfigStore customStore = mock(SessionConfigStore.class);
    this.contextRunner
        .withBean(SessionConfigStore.class, () -> customStore)
        .withBean(SessionConfigStoreFactory.class, () -> mock(SessionConfigStoreFactory.class))
        .run(
            context -> {
              assertThat(context).hasSingleBean(SessionConfigStore.class);
              assertThat(context.getBean(SessionConfigStore.class)).isSameAs(customStore);
            });
  }

  @Test
  void shouldCreateSessionConfigStoreFromFactory() {
    SessionConfigStoreFactory factory = mock(SessionConfigStoreFactory.class);
    SessionConfigStore storeFromFactory = mock(SessionConfigStore.class);
    when(factory.getStore()).thenReturn(storeFromFactory);

    this.contextRunner
        .withBean(SessionConfigStoreFactory.class, () -> factory)
        .run(
            context -> {
              assertThat(context).hasSingleBean(SessionConfigStore.class);
              assertThat(context.getBean(SessionConfigStore.class)).isSameAs(storeFromFactory);
            });
  }

  @Test
  void shouldBeInstantiable() {
    AppConfiguration config = new AppConfiguration();
    assertThat(config).isNotNull();
  }

  @Test
  void shouldRegisterSessionConfigProperties() {
    this.contextRunner
        .withBean(SessionConfigStoreFactory.class, () -> mock(SessionConfigStoreFactory.class))
        .run(context -> assertThat(context).hasSingleBean(SessionConfigProperties.class));
  }

  @Configuration
  static class CustomObjectMapperConfiguration {
    @Bean
    public ObjectMapper customObjectMapper() {
      return new ObjectMapper();
    }
  }
}
