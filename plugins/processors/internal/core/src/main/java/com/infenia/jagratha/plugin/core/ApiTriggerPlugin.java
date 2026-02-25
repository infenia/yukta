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
package com.infenia.jagratha.plugin.core;

import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.TriggerPlugin;
import com.infenia.jagratha.plugin.UiDesign;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A TriggerPlugin that emits the payload received from an API trigger. */
@Slf4j
@Component
public class ApiTriggerPlugin implements TriggerPlugin {

  /** Default constructor. */
  public ApiTriggerPlugin() {
    super();
  }

  @Override
  public String getDescription() {
    return "Emits the payload received from an API trigger.";
  }

  @Override
  public String getUsagePattern() {
    return "This plugin is automatically used when triggering a workflow via the REST API. "
        + "It passes the 'payload' map from the trigger request to the workflow.";
  }

  @Override
  public String getType() {
    return "api-trigger";
  }

  @Override
  public List<String> getOutputPorts() {
    return List.of("default");
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex flex-col items-center justify-center h-full space-y-1 relative">
                <span class="material-symbols-outlined text-blue-500 text-2xl">api</span>
                <div class="text-[10px] text-blue-600 font-bold uppercase tracking-widest">Trigger</div>
                <div class="jagratha-port absolute -right-3 top-1/2 -translate-y-1/2 w-4 h-4 bg-blue-600 rounded-full border-2 border-white shadow-sm flex items-center justify-center" data-port-name="default">
                    <div class="w-1.5 h-1.5 bg-white rounded-full"></div>
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
  public Flux<Message> start(final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final Map<String, Object> payload = ctx.get("payload");
          return Flux.just(Message.create(UUID.randomUUID(), payload));
        });
  }
}
