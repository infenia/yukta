// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import reactor.core.publisher.Mono;

/** Lets a plugin add a line to the live log of the execution it runs in. */
@FunctionalInterface
public interface ExecutionLog {

  /**
   * Appends a log line.
   *
   * @param executionId the execution the line belongs to
   * @param line the log line
   * @return a Mono that completes when the line is appended
   */
  Mono<Void> append(String executionId, String line);
}
