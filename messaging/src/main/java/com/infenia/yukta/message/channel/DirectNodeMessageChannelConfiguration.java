// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the default NodeMessageChannelProvider when no other is present. */
@Configuration
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
