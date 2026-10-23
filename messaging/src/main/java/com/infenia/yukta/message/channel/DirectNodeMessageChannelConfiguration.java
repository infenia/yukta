// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;

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
   * @param setting {@code yukta.messaging.max-payload-bytes}: largest payload a node may receive or
   *     send, as a byte count or a size such as {@code 64MB} (default 64 MiB); 0 turns the check
   *     off, a negative value is refused
   * @return the direct node message channel provider instance
   */
  @Bean
  @ConditionalOnMissingBean(NodeMessageChannelProvider.class)
  public NodeMessageChannelProvider directNodeMessageChannelProvider(
      @Value("${yukta.messaging.max-payload-bytes:67108864}") final String setting) {
    // parsed here, not bound as a DataSize, so it works without Boot's conversion service
    final DataSize maxPayload = DataSize.parse(setting.strip());
    if (maxPayload.isNegative()) {
      throw new IllegalArgumentException(
          "yukta.messaging.max-payload-bytes must be 0 (off) or positive, got " + maxPayload);
    }
    return new DirectNodeMessageChannelProvider(maxPayload.toBytes());
  }
}
