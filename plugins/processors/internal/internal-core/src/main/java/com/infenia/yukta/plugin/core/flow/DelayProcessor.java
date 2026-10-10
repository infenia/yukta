// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Holds each message back for a fixed time, which also rate-limits a stream to one per delay. */
@Component
@NoArgsConstructor
public final class DelayProcessor implements ProcessorPlugin {

  /** Delay ms. */
  private static final String DELAY_MS = "delayMs";

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Delays every message by a fixed time; messages are released one at a time, in order.",
        "Configure with:\n- delayMs: positive delay per message in milliseconds (required).",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-blue-100 text-blue-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><path d="M6 2h12M6 22h12M7 2v4l5 6-5 6v4M17 2v4l-5 6 5 6v4"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Delay</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                    </div>
                </div>
                """,
                140,
                80)));
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
