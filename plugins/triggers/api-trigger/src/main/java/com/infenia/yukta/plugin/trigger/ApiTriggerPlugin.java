// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** A TriggerPlugin that emits the payload received from an API trigger. */
@Slf4j
@Component
public class ApiTriggerPlugin implements TriggerPlugin {

  /** Default constructor. */
  public ApiTriggerPlugin() {
    super();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Emits the payload received from an API trigger.",
        "This plugin is automatically used when triggering a workflow via the REST API. "
            + "It passes the 'payload' map from the trigger request to the workflow.",
        Optional.of(new UiDesign("blue", "api", "Trigger", 140, 80)));
  }

  @Override
  public String getType() {
    return "API_TRIGGER";
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final Map<String, Object> payload = ctx.getOrDefault("payload", Map.of());
          return Flux.just(
              DefaultMessage.create(UUID.randomUUID(), payload).withSourcePort("default"));
        });
  }
}
