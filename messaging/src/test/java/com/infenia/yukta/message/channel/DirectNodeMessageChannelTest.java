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
  void inbound_passesMessagesThrough() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "payload");

    StepVerifier.create(channel.inbound("node-1", "exec-1", Flux.just(msg)))
        .expectNext(msg)
        .verifyComplete();
  }

  @Test
  void inbound_dropsExpiredButKeepsLiveAndUnexpiring() {
    final Message<?> expired = DefaultMessage.create(null, "a").withExpiration(1L);
    final Message<?> live =
        DefaultMessage.create(null, "b").withExpiration(System.currentTimeMillis() + 60_000);
    final Message<?> none = DefaultMessage.create(null, "c");

    StepVerifier.create(channel.inbound("n", "e", Flux.just(expired, live, none)))
        .expectNext(live, none)
        .verifyComplete();
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
  void limit_countsHeadersAndFailureTextToo() {
    final DirectNodeMessageChannel limited = new DirectNodeMessageChannel(8);
    final Message<?> headers =
        DefaultMessage.create(UUID.randomUUID(), "ok")
            .withMetadata(Map.of("trace", "x".repeat(20)));
    final Message<?> failure =
        DefaultMessage.create(UUID.randomUUID(), "ok").withFailure("n1", "r", "y".repeat(20));

    StepVerifier.create(limited.outbound("n1", "e1", Flux.just(headers)))
        .expectError(PayloadTooLargeException.class)
        .verify();
    StepVerifier.create(limited.outbound("n1", "e1", Flux.just(failure)))
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
  void inbound_nullNodeId_stillCompletes() {
    StepVerifier.create(channel.inbound(null, "exec-1", Flux.empty())).verifyComplete();
  }

  @Test
  void outbound_nullExecutionId_returnsFluxUnchanged() {
    final Flux<Message<?>> pluginOutput = Flux.empty();

    final Flux<Message<?>> result = channel.outbound("node-1", null, pluginOutput);

    assertThat(result).isSameAs(pluginOutput);
  }
}
