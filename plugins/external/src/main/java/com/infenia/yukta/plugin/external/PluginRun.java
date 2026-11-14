// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.message.Message;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** How a community plugin handles one message: a process per message, or an MCP tool call. */
public interface PluginRun {

  /**
   * Runs the plugin for a message and returns its output messages.
   *
   * @param message the input
   * @param config the node configuration
   * @return the output messages, in order
   */
  Flux<Message<?>> outputs(Message<?> message, Map<String, Object> config);

  /**
   * Runs the plugin for a message and ignores its output (a terminal).
   *
   * @param message the input
   * @param config the node configuration
   * @return completes when the plugin succeeded
   */
  Mono<Void> consume(Message<?> message, Map<String, Object> config);
}
