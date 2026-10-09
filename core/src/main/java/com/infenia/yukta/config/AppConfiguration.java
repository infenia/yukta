// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
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
@EnableConfigurationProperties(SessionConfigProperties.class)
@NoArgsConstructor
public class AppConfiguration {

  /** Upper bound on concurrently running virtual-thread tasks. */
  private static final int MAX_VIRTUAL_THREADS = 100_000;

  /** Seconds an idle virtual-thread worker is kept before being released. */
  private static final int VIRTUAL_THREAD_TTL_SECONDS = 60;

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
   * Provide a Scheduler that runs each task on its own virtual thread.
   *
   * <p>Backed by a bounded-elastic scheduler with a virtual-thread factory, so the number of
   * concurrent blocking plugin calls is not limited by the CPU count. It also schedules delayed
   * tasks, which node timeouts rely on.
   *
   * @return the virtual thread scheduler
   */
  @Bean(destroyMethod = "dispose")
  public Scheduler virtualThreadScheduler() {
    return Schedulers.newBoundedElastic(
        MAX_VIRTUAL_THREADS,
        Integer.MAX_VALUE,
        Thread.ofVirtual().name("yukta-virtual-", 0).factory(),
        VIRTUAL_THREAD_TTL_SECONDS);
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
