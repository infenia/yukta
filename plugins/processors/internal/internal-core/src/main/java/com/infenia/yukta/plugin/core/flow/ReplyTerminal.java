// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.gateway.ReplyGateway;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code REPLY}: answers the caller that started the run with {@code ?wait=} with the payload of
 * the first message that reaches it. A message without {@code replyTo} (nobody waits) is skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class ReplyTerminal implements TerminalPlugin {

  /** Answers the waiting caller; absent in a build without the server. */
  private final ObjectProvider<ReplyGateway> replies;

  @Override
  public String getType() {
    return "REPLY";
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Answers the caller of a webhook or API start made with ?wait=<seconds> with the payload"
            + " of the message.",
        "Takes no configuration. The first message that reaches it with a replyTo is the answer"
            + " (HTTP 200); later ones, and messages of a run nobody waits for, are skipped. A"
            + " caller that waits longer than ?wait= gets 202 and the execution id.",
        Optional.of(new UiDesign("green", "reply", "Reply", 140, 80)));
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input
        .doOnNext(message -> {
          final ReplyGateway gateway = replies.getIfAvailable();
          final boolean answered =
              message.replyTo() != null
                  && gateway != null
                  && gateway.reply(message.replyTo(), message.payload());
          if (!answered) {
            log.atDebug().addKeyValue("message", message.id()).log("Nobody waits for this reply");
          }
        })
        .then();
  }
}
