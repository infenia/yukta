// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.type;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Logic for side-effects. */
public interface TerminalPlugin extends Plugin {
  @Override
  default PluginCategory getCategory() {
    return PluginCategory.TERMINAL;
  }

  /**
   * Consume the input stream of messages.
   *
   * @param input the input Flux
   * @param config the plugin configuration
   * @return a Mono that completes when all messages are consumed
   */
  Mono<Void> consume(Flux<Message<?>> input, Map<String, Object> config);
}
