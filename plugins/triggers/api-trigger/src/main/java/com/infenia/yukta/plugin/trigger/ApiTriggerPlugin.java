// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TriggerPlugin;
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
            <div class="flex items-center w-full h-full bg-blue-50/50 border-2 border-blue-100 rounded-xl px-4 gap-3">
                <div class="flex-shrink-0 w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center text-blue-500">
                    <span class="material-symbols-outlined text-xl">api</span>
                </div>
                <div class="flex flex-col min-w-0">
                    <div class="text-[10px] text-blue-600 font-bold uppercase tracking-wider leading-none mb-1">Trigger</div>
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
  public Flux<Message<?>> start(final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final Map<String, Object> payload = ctx.get("payload");
          return Flux.just(
              DefaultMessage.create(UUID.randomUUID(), payload).withSourcePort("default"));
        });
  }
}
