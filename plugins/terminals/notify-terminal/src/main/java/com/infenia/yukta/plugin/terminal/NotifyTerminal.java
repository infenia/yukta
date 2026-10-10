// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Posts a {@code {"text": ...}} JSON notification (Slack/Teams/Discord-compatible) per message. */
@Component
@RequiredArgsConstructor
public class NotifyTerminal implements TerminalPlugin {

  /** Builder used to create the WebClient. */
  private final WebClient.Builder webClientBuilder;

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Sends each message as a JSON webhook notification (Slack-compatible).",
        "Configure with:\n"
            + "- url: webhook URL (required, http or https).\n"
            + "- text: fixed text to send; otherwise the payload's 'text' field, or the payload"
            + " itself as a string.\n"
            + "- timeoutMs: request timeout (default 10000).\n"
            + "A non-2xx response fails the node.",
        Optional.empty());
  }

  @Override
  public String getType() {
    return "NOTIFY_TERMINAL";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          final String url = String.valueOf(config.get("url"));
          if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("url must be an http(s) URL");
          }
        });
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String url = String.valueOf(config.get("url"));
    final Duration timeout =
        Duration.ofMillis(
            config.get("timeoutMs") instanceof Number millis ? millis.longValue() : 10_000L);
    return input
        .concatMap(
            message ->
                webClientBuilder
                    .build()
                    .post()
                    .uri(url)
                    .bodyValue(Map.of("text", text(message, config)))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(timeout))
        .then();
  }

  private static String text(final Message<?> message, final Map<String, Object> config) {
    final Object payload = message.payload();
    final Object fixed = config.get("text");
    final Object field = payload instanceof Map<?, ?> map ? map.get("text") : null;
    return String.valueOf(fixed != null ? fixed : field != null ? field : payload);
  }
}
