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

import java.util.Map;

/** Factory for obtaining the NodeMessageChannel to use for a given node. */
@FunctionalInterface
public interface NodeMessageChannelProvider {

  /**
   * Returns the channel to use for the given node. Called once per node assembly.
   *
   * @param nodeId the node identifier
   * @param config the node configuration
   * @return the channel to use for this node
   */
  NodeMessageChannel channelFor(String nodeId, Map<String, Object> config);
}
