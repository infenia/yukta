// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.impl.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.logging.api.PluginLogStoreConfig;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LogStoreAutoConfiguration}. */
@NoArgsConstructor
@DisplayName("LogStoreAutoConfiguration")
class LogStoreAutoConfigurationTest {

  @Test
  @DisplayName("inMemoryPluginLogStore bean method creates instance")
  void inMemoryPluginLogStore_createsBeanFromMethod() {
    final PluginLogStoreConfig config = new PluginLogStoreConfig();
    final LogStoreAutoConfiguration autoConfig = new LogStoreAutoConfiguration();

    final PluginLogStore store = autoConfig.inMemoryPluginLogStore(config);

    assertThat(store).isNotNull().isInstanceOf(InMemoryPluginLogStore.class);
  }

  @Test
  @DisplayName("configuration can be instantiated")
  void autoConfiguration_canBeInstantiated() {
    final LogStoreAutoConfiguration config = new LogStoreAutoConfiguration();
    assertThat(config).isNotNull();
  }

  @Test
  @DisplayName("retention maximum is 1440 minutes")
  void config_maxRetentionIs1440() {
    final PluginLogStoreConfig config = new PluginLogStoreConfig();
    final int maxMinutes = config.getMaxRetentionMinutes();
    assertThat(maxMinutes).isPositive();
    assertThat(maxMinutes).isGreaterThanOrEqualTo(1440);
  }
}
