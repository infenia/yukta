// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import com.infenia.yukta.message.Message;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * Runs the work of a processor node on a worker. The engine keeps everything around the plugin call
 * (retries, error routing, timeouts, valves, checkpoints, status); only the call itself goes to the
 * worker. Provided by the {@code workers} module; without it nodes cannot be placed.
 */
@FunctionalInterface
public interface RemoteNodeExecutor {

  /**
   * Runs one node attempt on a worker of a pool.
   *
   * <p>The execution and node ids come from the Reactor context ({@code executionId}, {@code
   * nodeId}), as they do for a local plugin. Cancelling the returned flux cancels the work on the
   * worker; messages are carried as plain JSON values.
   *
   * @param pluginType the plugin type to run
   * @param pool the pool of workers that may run it
   * @param input the node's input
   * @param config the node's configuration
   * @return the node's output
   */
  Flux<Message<?>> run(
      String pluginType, String pool, Flux<Message<?>> input, Map<String, Object> config);
}
