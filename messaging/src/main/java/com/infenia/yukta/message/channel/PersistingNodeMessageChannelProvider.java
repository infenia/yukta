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

import com.infenia.yukta.message.store.MessageStore;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provider that returns a persisting message channel when a MessageStore is available.
 *
 * <p>All nodes receive the same persisting channel instance which wires message storage into the
 * outbound message path.
 */
@Slf4j
@RequiredArgsConstructor
public class PersistingNodeMessageChannelProvider implements NodeMessageChannelProvider {

  /** The message store for persisting messages. */
  private final MessageStore messageStore;

  @Override
  public NodeMessageChannel channelFor(final String nodeId, final Map<String, Object> config) {
    log.atDebug().setMessage("Creating persisting channel for node: {}").addArgument(nodeId).log();
    return new PersistingNodeMessageChannel(messageStore);
  }
}
