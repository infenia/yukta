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
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.Message;
import reactor.core.publisher.Flux;

/** Transport abstraction between inter-node boundaries. */
public interface NodeMessageChannel {

  /**
   * Wraps the upstream Flux before the plugin receives it. Called by Processor and Terminal
   * assembler strategies.
   *
   * @param nodeId the node identifier
   * @param executionId the execution identifier
   * @param upstream the incoming message stream from parent nodes
   * @return the message stream to deliver to the plugin
   */
  Flux<Message<?>> inbound(String nodeId, String executionId, Flux<Message<?>> upstream);

  /**
   * Wraps the plugin's output Flux before it reaches downstream nodes. Called by Trigger and
   * Processor assembler strategies.
   *
   * @param nodeId the node identifier
   * @param executionId the execution identifier
   * @param pluginOutput the message stream produced by the plugin
   * @return the message stream to deliver to downstream nodes
   */
  Flux<Message<?>> outbound(String nodeId, String executionId, Flux<Message<?>> pluginOutput);
}
