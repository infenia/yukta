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

/**
 * Factory for creating PluginLogger instances.
 *
 * <p>Implementations should create loggers configured for specific execution contexts.
 */
@FunctionalInterface
public interface PluginLoggerFactory {

  /**
   * Create a logger for a specific execution.
   *
   * @param context the logger creation context
   * @return a new PluginLogger instance
   */
  PluginLogger create(LoggerContext context);

  /**
   * Create a logger with default plugin name.
   *
   * @param executionId the execution identifier
   * @param sessionId the session identifier
   * @param pluginId the plugin type/identifier
   * @return a new PluginLogger instance
   */
  default PluginLogger create(
      final String executionId, final String sessionId, final String pluginId) {
    return create(new LoggerContext(executionId, sessionId, pluginId, pluginId));
  }

  /**
   * Create a logger with explicit plugin name.
   *
   * @param executionId the execution identifier
   * @param sessionId the session identifier
   * @param pluginId the plugin type/identifier
   * @param pluginName the plugin display name
   * @return a new PluginLogger instance
   */
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
  default PluginLogger create(
      final String executionId,
      final String sessionId,
      final String pluginId,
      final String pluginName) {
    return create(new LoggerContext(executionId, sessionId, pluginId, pluginName));
  }

  /**
   * Context for creating a PluginLogger.
   *
   * @param executionId the execution identifier
   * @param sessionId the session identifier
   * @param pluginId the plugin type/identifier
   * @param pluginName the plugin display name (optional)
   */
  record LoggerContext(String executionId, String sessionId, String pluginId, String pluginName) {}
}
