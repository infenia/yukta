// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Holds each message back for a fixed time, which also rate-limits a stream to one per delay. */
@Component
public final class DelayProcessor implements ProcessorPlugin {

  private static final String DELAY_MS = "delayMs";

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Delays every message by a fixed time; messages are released one at a time, in order.",
        "Configure with:\n- delayMs: positive delay per message in milliseconds (required).",
        Optional.empty());
  }

  @Override
  public String getType() {
    return "DELAY";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> delay(config));
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.delayElements(delay(config));
  }

  private static Duration delay(final Map<String, Object> config) {
    if (config.get(DELAY_MS) instanceof Number millis && millis.longValue() > 0) {
      return Duration.ofMillis(millis.longValue());
    }
    throw new IllegalArgumentException("delayMs must be a positive number");
  }
}
