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
