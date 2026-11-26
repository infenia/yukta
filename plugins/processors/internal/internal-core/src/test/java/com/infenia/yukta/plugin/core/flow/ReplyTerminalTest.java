// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.gateway.ReplyGateway;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SuppressWarnings({"unchecked", "PMD.TooManyStaticImports"})
class ReplyTerminalTest {

  private final ReplyGateway gateway = mock(ReplyGateway.class);
  private final ObjectProvider<ReplyGateway> provider = mock(ObjectProvider.class);

  @Test
  void answersTheReplyToOfEachMessage() {
    when(provider.getIfAvailable()).thenReturn(gateway);
    when(gateway.reply("k1", "done")).thenReturn(true);

    StepVerifier.create(
            new ReplyTerminal(provider)
                .consume(
                    Flux.just(
                        DefaultMessage.create(null, "done").withReplyTo("k1"),
                        DefaultMessage.create(null, "late").withReplyTo("gone"),
                        DefaultMessage.create(null, "nobody")),
                    Map.of()))
        .verifyComplete();

    verify(gateway).reply("k1", "done");
    verify(gateway).reply("gone", "late");
    verifyNoMoreInteractions(gateway);
  }

  @Test
  void withoutGatewayItSkipsTheMessage() {
    StepVerifier.create(
            new ReplyTerminal(provider)
                .consume(Flux.just(DefaultMessage.create(null, "x").withReplyTo("k")), Map.of()))
        .verifyComplete();
    assertThat(new ReplyTerminal(provider).getType()).isEqualTo("REPLY");
    assertThat(new ReplyTerminal(provider).describe().description()).contains("?wait=");
  }
}
