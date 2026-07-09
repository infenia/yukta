// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.type;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import java.util.Map;
import reactor.core.publisher.Flux;

/** Logic for transforming, filtering, or splitting data. */
public interface ProcessorPlugin extends Plugin {
  @Override
  default PluginCategory getCategory() {
    return PluginCategory.PROCESSOR;
  }

  /**
   * Process the input stream of messages.
   *
   * @param input the input Flux
   * @param config the plugin configuration
   * @return the transformed Flux
   */
  Flux<Message<?>> process(Flux<Message<?>> input, Map<String, Object> config);
}
