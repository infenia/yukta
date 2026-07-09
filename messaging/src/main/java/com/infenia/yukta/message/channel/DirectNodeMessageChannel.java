// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.Message;
import reactor.core.publisher.Flux;

/** Zero-overhead passthrough channel. Both sides return the Flux unchanged. */
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class DirectNodeMessageChannel implements NodeMessageChannel {

  @Override
  public Flux<Message<?>> inbound(
      final String nodeId, final String executionId, final Flux<Message<?>> upstream) {
    return upstream;
  }

  @Override
  public Flux<Message<?>> outbound(
      final String nodeId, final String executionId, final Flux<Message<?>> pluginOutput) {
    return pluginOutput;
  }
}
