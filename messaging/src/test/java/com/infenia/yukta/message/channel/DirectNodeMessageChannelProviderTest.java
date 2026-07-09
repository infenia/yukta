// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link DirectNodeMessageChannelProvider}. */
@NoArgsConstructor
class DirectNodeMessageChannelProviderTest {

  /** Provider instance for testing. */
  private final DirectNodeMessageChannelProvider provider = new DirectNodeMessageChannelProvider();

  @Test
  void channelFor_returnsDirectNodeMessageChannel() {
    final NodeMessageChannel channel = provider.channelFor("node-1", Map.of());

    assertThat(channel).isInstanceOf(DirectNodeMessageChannel.class);
  }

  @Test
  void channelFor_multipleCalls_returnSameInstance() {
    final NodeMessageChannel first = provider.channelFor("node-1", Map.of());
    final NodeMessageChannel second = provider.channelFor("node-2", Map.of("key", "value"));

    assertThat(first).isSameAs(second);
  }

  @Test
  void channelFor_withNullConfig_doesNotThrow() {
    assertThatNoException()
        .isThrownBy(
            () -> {
              assertThat(provider.channelFor("node-1", null)).isNotNull();
            });
  }
}
