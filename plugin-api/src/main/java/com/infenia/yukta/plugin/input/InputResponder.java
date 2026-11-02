// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Delivers a request to a tier of its route, such as a chat channel or a webhook. Register an
 * implementation as a Spring bean. The answer does not come back through this interface: the
 * receiver answers with an {@code AnswerInputCommand}, so it passes the same review and audit as
 * any other answer.
 *
 * <p>Delivery is at least once, so an implementation must be idempotent by request id.
 *
 * @since 0.1.0-alpha.4
 */
public interface InputResponder {

  /**
   * The name a route uses to select this responder, as in {@code external:<type>}.
   *
   * @return the responder type
   */
  String type();

  /**
   * Delivers the request, already redacted.
   *
   * @param request the request as it is now
   * @param tierSettings the settings of the tier that targets this responder
   * @return completes when the receiver has the request; an error is retried with backoff
   */
  Mono<Void> deliver(InputRequest request, Map<String, Object> tierSettings);
}
