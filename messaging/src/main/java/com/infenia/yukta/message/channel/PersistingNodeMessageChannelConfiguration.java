// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.store.MessageStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the PersistingNodeMessageChannelProvider when a MessageStore bean is available.
 *
 * <p>This configuration enables message persistence at the channel layer, providing a clean
 * separation between topology orchestration and message auditing concerns.
 */
@Configuration
@ConditionalOnBean(MessageStore.class)
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class PersistingNodeMessageChannelConfiguration {

  /**
   * Creates the persisting channel provider bean when a MessageStore is available.
   *
   * @param messageStore the message store bean for persisting messages
   * @return the persisting node message channel provider instance
   */
  @Bean
  public NodeMessageChannelProvider persistingNodeMessageChannelProvider(
      final MessageStore messageStore) {
    return new PersistingNodeMessageChannelProvider(messageStore);
  }
}
