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

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Tests for {@link DirectNodeMessageChannel}. */
@NoArgsConstructor
class DirectNodeMessageChannelTest {

  /** Channel instance for testing. */
  private final DirectNodeMessageChannel channel = new DirectNodeMessageChannel();

  @Test
  void inbound_returnsUpstreamFluxUnchanged() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "payload");
    final Flux<Message<?>> upstream = Flux.just(msg);

    final Flux<Message<?>> result = channel.inbound("node-1", "exec-1", upstream);

    assertThat(result).isSameAs(upstream);
  }

  @Test
  void outbound_returnsPluginOutputFluxUnchanged() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "payload");
    final Flux<Message<?>> pluginOutput = Flux.just(msg);

    final Flux<Message<?>> result = channel.outbound("node-1", "exec-1", pluginOutput);

    assertThat(result).isSameAs(pluginOutput);
  }

  @Test
  void inbound_nullNodeId_returnsFluxUnchanged() {
    final Flux<Message<?>> upstream = Flux.empty();

    final Flux<Message<?>> result = channel.inbound(null, "exec-1", upstream);

    assertThat(result).isSameAs(upstream);
  }

  @Test
  void outbound_nullExecutionId_returnsFluxUnchanged() {
    final Flux<Message<?>> pluginOutput = Flux.empty();

    final Flux<Message<?>> result = channel.outbound("node-1", null, pluginOutput);

    assertThat(result).isSameAs(pluginOutput);
  }
}
