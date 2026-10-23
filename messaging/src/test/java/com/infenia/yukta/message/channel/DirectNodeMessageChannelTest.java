// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

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
  void inbound_payloadOverLimit_failsWithPayloadTooLarge() {
    final DirectNodeMessageChannel limited = new DirectNodeMessageChannel(4);
    final Flux<Message<?>> upstream = Flux.just(DefaultMessage.create(UUID.randomUUID(), "12345"));

    StepVerifier.create(limited.inbound("n1", "e1", upstream))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(PayloadTooLargeException.class)
                    .hasMessageContaining("'n1'")
                    .hasMessageContaining("5 bytes")
                    .hasMessageContaining("limit of 4"))
        .verify();
  }

  @Test
  void outbound_nestedPayloadOverLimit_failsAndSmallOnesPass() {
    final DirectNodeMessageChannel limited = new DirectNodeMessageChannel(8);
    final Map<String, Object> big = Map.of("k", List.of(new byte[6], "abc"));

    StepVerifier.create(
            limited.outbound(
                "n1",
                "e1",
                Flux.just(
                    DefaultMessage.create(UUID.randomUUID(), "ok"),
                    DefaultMessage.create(UUID.randomUUID(), big))))
        .expectNextCount(1)
        .expectError(PayloadTooLargeException.class)
        .verify();
  }

  @Test
  void limit_nonMeasuredPayloadAndNull_pass() {
    final DirectNodeMessageChannel limited = new DirectNodeMessageChannel(1);

    StepVerifier.create(
            limited.inbound(
                "n1",
                "e1",
                Flux.just(
                    DefaultMessage.create(UUID.randomUUID(), 12_345_678),
                    DefaultMessage.create(UUID.randomUUID(), (Object) null))))
        .expectNextCount(2)
        .verifyComplete();
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
