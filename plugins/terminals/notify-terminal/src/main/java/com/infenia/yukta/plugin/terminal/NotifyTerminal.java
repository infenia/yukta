// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.net.URI;
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
            + "- url: webhook URL (required, https; http only for localhost).\n"
            + "- text: fixed text to send; otherwise the payload's 'text' field, or the payload"
            + " itself as a string.\n"
            + "- timeoutMs: request timeout (default 10000).\n"
            + "A non-2xx response (including redirects) fails the node.",
        Optional.empty());
  }

  @Override
  public String getType() {
    return "NOTIFY_TERMINAL";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> checkUrl(String.valueOf(config.get("url"))));
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String url = String.valueOf(config.get("url"));
    checkUrl(url);
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
                    .exchangeToMono(
                        response ->
                            response
                                .releaseBody()
                                .then(
                                    response.statusCode().is2xxSuccessful()
                                        ? Mono.empty()
                                        : Mono.error(
                                            new IllegalStateException(
                                                "Webhook answered " + response.statusCode()))))
                    .timeout(timeout))
        .then();
  }

  /** Webhooks may carry secrets, so only https is allowed, plus http to the local machine. */
  private static void checkUrl(final String url) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("url is not a valid URL", e);
    }
    final String host = String.valueOf(uri.getHost());
    final boolean loopback =
        "localhost".equalsIgnoreCase(host) || "[::1]".equals(host) || host.startsWith("127.");
    if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
      throw new IllegalArgumentException("url must be https (http only for localhost)");
    }
  }

  private static String text(final Message<?> message, final Map<String, Object> config) {
    final Object payload = message.payload();
    final Object fixed = config.get("text");
    final Object field = payload instanceof Map<?, ?> map ? map.get("text") : null;
    return String.valueOf(fixed != null ? fixed : field != null ? field : payload);
  }
}
