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
package com.infenia.yukta.logging.impl.memory;

import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.logging.api.PluginLogStoreConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for plugin log storage.
 *
 * <p>Provides default in-memory implementation. Future: file-based and database backends can be
 * added with their own auto-configurations gated by property conditions.
 */
@Configuration
public class LogStoreAutoConfiguration {

  /** Default constructor. */
  public LogStoreAutoConfiguration() {
    // No-op constructor for Spring instantiation
  }

  /**
   * Create in-memory log store bean.
   *
   * <p>Active by default when no other PluginLogStore bean exists. Can be overridden by setting
   * `yukta.logs.store.backend=file` or `database` (when those implementations are available).
   *
   * @param config the log store configuration
   * @return the log store instance
   */
  @Bean
  @ConditionalOnMissingBean(PluginLogStore.class)
  @ConditionalOnProperty(
      name = "yukta.logs.store.backend",
      havingValue = "memory",
      matchIfMissing = true)
  public PluginLogStore inMemoryPluginLogStore(final PluginLogStoreConfig config) {
    return new InMemoryPluginLogStore(config);
  }
}
