// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Fluent interface for logging from plugins during execution.
 *
 * <p>Provides methods for logging to stdout, stderr, and custom streams with optional metadata.
 */
public interface PluginLogger {

  /**
   * Log a message to stdout.
   *
   * @param message the log message
   * @return a Mono that completes when the message is queued
   */
  Mono<Void> logStdout(String message);

  /**
   * Log a message to stdout with metadata.
   *
   * @param message the log message
   * @param metadata optional context metadata
   * @return a Mono that completes when the message is queued
   */
  Mono<Void> logStdout(String message, Map<String, Object> metadata);

  /**
   * Log a message to stderr.
   *
   * @param message the log message
   * @return a Mono that completes when the message is queued
   */
  Mono<Void> logStderr(String message);

  /**
   * Log a message to stderr with metadata.
   *
   * @param message the log message
   * @param metadata optional context metadata
   * @return a Mono that completes when the message is queued
   */
  Mono<Void> logStderr(String message, Map<String, Object> metadata);

  /**
   * Log a message to a custom stream.
   *
   * @param customStreamName the custom stream name
   * @param message the log message
   * @return a Mono that completes when the message is queued
   */
  Mono<Void> logCustom(String customStreamName, String message);

  /**
   * Log a message to a custom stream with metadata.
   *
   * @param customStreamName the custom stream name
   * @param message the log message
   * @param metadata optional context metadata
   * @return a Mono that completes when the message is queued
   */
  Mono<Void> logCustom(String customStreamName, String message, Map<String, Object> metadata);

  /**
   * Close the logger and flush any pending messages.
   *
   * @return a Mono that completes when the logger is closed
   */
  Mono<Void> close();
}
