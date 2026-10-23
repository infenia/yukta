// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A simple TerminalPlugin that logs message payloads to the console. Payloads can carry personal
 * data or secrets, so they are logged at DEBUG; at INFO only a count of consumed messages appears.
 */
@Slf4j
@Component
public class ConsoleTerminalPlugin implements TerminalPlugin {

  /** Default constructor. */
  public ConsoleTerminalPlugin() {
    super();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Logs message payloads to the console/logger (at DEBUG).",
        "Consumes messages and prints their payload to the application logs at DEBUG level; INFO"
            + " shows only a message count. No configuration required.",
        Optional.of(new UiDesign("slate", "terminal", "Logger", 140, 80)));
  }

  @Override
  public String getType() {
    return "CONSOLE_TERMINAL";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Mono<Void> initialize(final Map<String, Object> config) {
    return Mono.empty();
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input
        .doOnNext(
            msg -> {
              if (log.isDebugEnabled()) {
                log.debug("Consuming message: {}", msg.payload());
              }
            })
        .count()
        .doOnNext(count -> log.info("Consumed {} message(s)", count))
        .then();
  }
}
