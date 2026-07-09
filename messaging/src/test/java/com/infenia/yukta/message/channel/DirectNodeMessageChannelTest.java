// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
