// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

import reactor.core.publisher.Mono;

/** Interface for detecting and preventing duplicate message processing. */
public interface IdempotencyStore {

  /**
   * Check if a message has already been processed.
   *
   * @param messageId the unique message identifier
   * @return a Mono emitting true if the message is a duplicate
   */
  @SuppressWarnings("PMD.LinguisticNaming")
  Mono<Boolean> isDuplicate(String messageId);

  /**
   * Mark a message as processed.
   *
   * @param messageId the unique message identifier
   * @return a Mono that completes when the message is recorded
   */
  Mono<Void> markProcessed(String messageId);
}
