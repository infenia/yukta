/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.plugin.core;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.TerminalPlugin;
import com.infenia.yukta.plugin.UiDesign;
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
    return "console";
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex flex-col items-center justify-center h-full space-y-1">
                <span class="material-symbols-outlined text-slate-400 text-2xl">terminal</span>
                <div class="text-[10px] text-slate-500 font-mono">Logger</div>
            </div>
            """,
            120,
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
  public Mono<Void> consume(final Flux<Message> input, final Map<String, Object> config) {
    return input.doOnNext(msg -> log.info("Consuming message: {}", msg.payload())).then();
  }
}
