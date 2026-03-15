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

import com.infenia.yukta.service.session.SessionConfigStore;
import com.infenia.yukta.service.session.SessionConfigStoreFactory;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/** Configuration for the application. */
@Configuration
@EnableConfigurationProperties(SessionConfigProperties.class)
public class AppConfiguration {

  /** Public constructor for PMD. */
  public AppConfiguration() {
    super();
  }

  /**
   * Provide an ObjectMapper bean if not already present.
   *
   * @return the ObjectMapper
   */
  @Bean
  @ConditionalOnMissingBean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  /**
   * Provide a Scheduler that uses virtual threads.
   *
   * @return the virtual thread scheduler
   */
  @Bean
  @SuppressWarnings("PMD.DoNotUseThreads")
  public Scheduler virtualThreadScheduler() {
    return Schedulers.fromExecutorService(
        Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(), Thread.ofVirtual().factory()));
  }

  /**
   * Global heartbeat interval for nodes in seconds.
   *
   * @return the heartbeat interval duration
   */
  @Bean
  public Duration heartbeatInterval() {
    return Duration.ofSeconds(10);
  }

  /**
   * Provide the configured SessionConfigStore instance based on store-type property.
   *
   * @param factory the store factory
   * @return the configured SessionConfigStore
   */
  @Bean
  @ConditionalOnMissingBean
  public SessionConfigStore sessionConfigStore(final SessionConfigStoreFactory factory) {
    return factory.getStore();
  }
}
