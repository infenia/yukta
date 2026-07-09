// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.type;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import java.util.Map;
import reactor.core.publisher.Flux;

/** Produces data from an external source. */
public interface TriggerPlugin extends Plugin {
  @Override
  default PluginCategory getCategory() {
    return PluginCategory.TRIGGER;
  }

  /**
   * Start producing data.
   *
   * @param config the plugin configuration
   * @return a Flux of messages
   */
  Flux<Message<?>> start(Map<String, Object> config);
}
