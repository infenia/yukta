// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import reactor.core.publisher.Mono;

/**
 * Decides who may answer or hand up which input request. It is the security boundary of input
 * requests: a processor calls it before it takes an answer. The default is {@link
 * DefaultAnswerAuthorizer}; an application replaces it by defining a bean of its own.
 */
@FunctionalInterface
public interface AnswerAuthorizer {

  /**
   * Checks the sender.
   *
   * @param context the request and the sender
   * @return completes when the sender may go on; errors with {@link
   *     com.infenia.yukta.plugin.input.InputForbiddenException} naming the rule that failed
   */
  Mono<Void> authorize(AnswerContext context);
}
