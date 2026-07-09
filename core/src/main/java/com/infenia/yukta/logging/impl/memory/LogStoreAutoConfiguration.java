// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
