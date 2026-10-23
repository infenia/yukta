// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the default NodeMessageChannelProvider when no other is present. This is an
 * auto-configuration (listed in {@code AutoConfiguration.imports}), so it is processed after every
 * user configuration and {@code @ConditionalOnMissingBean} sees a custom provider whatever order
 * Spring reads the classes in.
 */
@AutoConfiguration
@NoArgsConstructor
public class DirectNodeMessageChannelConfiguration {

  /**
   * Creates the default DirectNodeMessageChannelProvider bean when no other
   * NodeMessageChannelProvider is available.
   *
   * @return the direct node message channel provider instance
   */
  @Bean
  @ConditionalOnMissingBean(NodeMessageChannelProvider.class)
  public NodeMessageChannelProvider directNodeMessageChannelProvider() {
    return new DirectNodeMessageChannelProvider();
  }
}
