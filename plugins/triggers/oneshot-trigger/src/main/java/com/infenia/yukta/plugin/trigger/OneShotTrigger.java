// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Fires a workflow with no input. Emits a single empty message and ignores any caller data. */
@Slf4j
@Component
public class OneShotTrigger implements TriggerPlugin {

  /** Default constructor. */
  public OneShotTrigger() {
    super();
  }

  @Override
  public String getType() {
    return "ONE_SHOT";
  }

  @Override
  public String getDescription() {
    return "Fires a workflow with no input. Emits a single empty message to start execution.";
  }

  @Override
  public String getUsagePattern() {
    return "No configuration required. Call the workflow trigger endpoint to start execution.";
  }

  @Override
  public Optional<UiDesign> getUiDesign() {
    return Optional.of(
        new UiDesign(
            """
            <div class="flex items-center w-full h-full bg-green-50/50 border-2 border-green-100 rounded-xl px-4 gap-3">
                <div class="flex-shrink-0 w-8 h-8 bg-green-100 rounded-lg flex items-center justify-center text-green-500">
                    <span class="material-symbols-outlined text-xl">touch_app</span>
                </div>
                <div class="flex flex-col min-w-0">
                    <div class="text-[10px] text-green-600 font-bold uppercase tracking-wider leading-none mb-1">One-Shot</div>
                    <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                </div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    log.atDebug().log("OneShotTrigger firing: emitting empty message");
    return Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of()).withSourcePort("default"));
  }
}
