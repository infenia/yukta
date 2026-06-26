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

import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/** Configuration for the application. */
@Configuration
@EnableConfigurationProperties({SessionConfigProperties.class, YuktaProperties.class})
@NoArgsConstructor
public class AppConfiguration {

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
  public Scheduler virtualThreadScheduler() {
    return Schedulers.fromExecutorService(
        Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(), Thread.ofVirtual().factory()));
  }

  /**
   * Global heartbeat interval for nodes in seconds.
   *
   * @param properties the Yukta configuration properties
   * @return the heartbeat interval duration
   */
  @Bean
  public Duration heartbeatInterval(final YuktaProperties properties) {
    return Duration.ofSeconds(properties.getHeartbeatIntervalSeconds());
  }

  /**
   * Provide the ExecutionControlStore bean.
   *
   * <p>In-memory implementation suitable for single-node deployments. Override this bean to use
   * alternative backends (Redis, database, etc.).
   *
   * @return the execution control store instance
   */
  @Bean
  @ConditionalOnMissingBean
  public ExecutionControlStore executionControlStore() {
    return new InMemoryExecutionControlStore();
  }
}
