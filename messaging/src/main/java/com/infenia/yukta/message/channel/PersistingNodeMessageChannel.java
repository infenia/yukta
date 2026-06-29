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
import com.infenia.yukta.message.store.MessageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Channel implementation that persists messages to a MessageStore.
 *
 * <p>Wraps outbound messages through a wire-tap to the MessageStore for auditing, debugging, and
 * message history reconstruction. Inbound messages pass through unchanged.
 */
@Slf4j
@RequiredArgsConstructor
public class PersistingNodeMessageChannel implements NodeMessageChannel {

  /** The message store for persisting messages. */
  private final MessageStore messageStore;

  @Override
  public Flux<Message<?>> inbound(
      final String nodeId, final String executionId, final Flux<Message<?>> upstream) {
    return upstream;
  }

  @Override
  public Flux<Message<?>> outbound(
      final String nodeId, final String executionId, final Flux<Message<?>> pluginOutput) {
    log.atDebug()
        .setMessage("Applying message store wire-tap for node: {}")
        .addArgument(nodeId)
        .log();
    return pluginOutput.flatMap(msg -> messageStore.store(msg).thenReturn(msg));
  }
}
