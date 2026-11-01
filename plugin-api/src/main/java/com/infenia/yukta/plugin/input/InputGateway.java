// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import reactor.core.publisher.Mono;

/**
 * Lets a plugin ask a supervisor, a person or an external agent for input and wait for the answer.
 *
 * <p>The engine provides one implementation as a Spring bean; a plugin takes it through an {@code
 * ObjectProvider}, the way it takes the {@code SubWorkflowGateway}, and uses it only while a node
 * runs. The run and the node that ask come from the Reactor context the engine writes ({@link
 * InputIdentity}), so a plugin cannot ask in the name of another node.
 *
 * @since 0.1.0-alpha.4
 */
@FunctionalInterface
public interface InputGateway {

  /**
   * Asks and waits. Subscribing again with the same {@link InputRequestSpec#key()} joins the same
   * request, or returns the earlier answer. Cancelling the subscription cancels the request.
   *
   * @param spec the question
   * @return the answer; fails with {@link InputExpiredException} when nobody answered in time,
   *     {@link InputCancelledException} when the run or the node ended first, {@link
   *     InputRejectedException} when the request is refused, or {@link InputLimitException} when
   *     the run asks too much
   */
  Mono<InputResponse> ask(InputRequestSpec spec);
}
