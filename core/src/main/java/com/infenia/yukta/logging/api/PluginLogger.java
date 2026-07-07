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
