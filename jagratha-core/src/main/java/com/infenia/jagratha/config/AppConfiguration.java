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
package com.infenia.jagratha.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Configuration for the application. */
@Configuration
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
    return Schedulers.fromExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }
}
