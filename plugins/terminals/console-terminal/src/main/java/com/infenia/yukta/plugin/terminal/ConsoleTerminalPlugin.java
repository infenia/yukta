// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A simple TerminalPlugin that logs message payloads to the console. */
@Slf4j
@Component
public class ConsoleTerminalPlugin implements TerminalPlugin {

  /** Default constructor. */
  public ConsoleTerminalPlugin() {
    super();
  }

  @Override
  public String getDescription() {
    return "Logs message payloads to the console/logger.";
  }

  @Override
  public String getUsagePattern() {
    return "Consumes messages and prints their payload to the application logs. No configuration"
        + " required.";
  }

  @Override
  public String getType() {
    return "CONSOLE_TERMINAL";
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex items-center w-full h-full bg-slate-50 border-2 border-slate-200 rounded-xl px-4 gap-3">
                <div class="flex-shrink-0 w-8 h-8 bg-slate-100 rounded-lg flex items-center justify-center text-slate-500">
                    <span class="material-symbols-outlined text-xl">terminal</span>
                </div>
                <div class="flex flex-col min-w-0">
                    <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Logger</div>
                    <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                </div>
            </div>
            """,
            140,
            80));
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
    return input.doOnNext(msg -> log.info("Consuming message: {}", msg.getPayload())).then();
  }
}
