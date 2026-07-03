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
public class ManualTrigger implements TriggerPlugin {

  /** Default constructor. */
  public ManualTrigger() {
    super();
  }

  @Override
  public String getType() {
    return "MANUAL";
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
                    <div class="text-[10px] text-green-600 font-bold uppercase tracking-wider leading-none mb-1">Manual</div>
                    <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                </div>
            </div>
            """,
            140,
            80));
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    log.atDebug().log("ManualTrigger firing: emitting empty message");
    return Flux.just(DefaultMessage.create(UUID.randomUUID(), Map.of()).withSourcePort("default"));
  }
}
