// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.store.MessageStore;
import java.util.Collections;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;

/** Tests for {@link PersistingNodeMessageChannelProvider}. */
@MockitoSettings
@NoArgsConstructor
class PersistingNodeMessageChannelProviderTest {

  /** Mock message store for testing. */
  @Mock private MessageStore messageStore;

  /** Provider instance under test. */
  private PersistingNodeMessageChannelProvider provider;

  @BeforeEach
  void setUp() {
    provider = new PersistingNodeMessageChannelProvider(messageStore);
  }

  @Test
  void channelForReturnsPersisingChannel() {
    final NodeMessageChannel channel = provider.channelFor("node-1", Collections.emptyMap());

    assertThat(channel).isInstanceOf(PersistingNodeMessageChannel.class);
  }

  @Test
  void channelForMultipleNodesReturnsPersistingChannels() {
    final NodeMessageChannel channel1 = provider.channelFor("node-1", Collections.emptyMap());
    final NodeMessageChannel channel2 = provider.channelFor("node-2", Collections.emptyMap());

    assertThat(channel1).isInstanceOf(PersistingNodeMessageChannel.class);
    assertThat(channel2).isInstanceOf(PersistingNodeMessageChannel.class);
  }
}
