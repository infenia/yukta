// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message.channel;

import com.infenia.yukta.message.Message;
import reactor.core.publisher.Flux;

/**
 * Transport abstraction between inter-node boundaries. It decorates the in-process streams of one
 * run; it is not (yet) a contract for a network or durable transport.
 *
 * <p>Contract for an implementation:
 *
 * <ul>
 *   <li><b>Failure:</b> an error on the returned Flux fails the node like a plugin error; an
 *       implementation must not swallow it.
 *   <li><b>Dropping:</b> it may drop a message only when it says so (log or metric); the default
 *       channel drops only expired messages inbound.
 *   <li><b>Back-pressure:</b> it must pass Reactor demand through unchanged and not buffer without
 *       bound.
 *   <li><b>Encoding:</b> messages are live objects; nothing is serialised here. A transport that
 *       serialises must keep the JSON-values rule for headers (see {@link Message#metadata()}) and
 *       cap payload size itself.
 *   <li><b>Acknowledgement and redelivery:</b> not part of the SPI.
 * </ul>
 */
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
