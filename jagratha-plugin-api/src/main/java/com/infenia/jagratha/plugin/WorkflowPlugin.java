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
package com.infenia.jagratha.plugin;

import java.util.Map;
import reactor.core.publisher.Mono;

/** Base interface for all workflow plugins. */
public interface WorkflowPlugin {
  /**
   * Get the unique type string for this plugin.
   *
   * @return the plugin type
   */
  String getType();

  /**
   * Get the category of this plugin.
   *
   * @return the plugin category
   */
  PluginCategory getCategory();

  /**
   * Validate the plugin configuration.
   *
   * @param config the configuration to validate
   * @return a Mono that completes if valid, or emits an error
   */
  default Mono<Void> validateConfig(Map<String, Object> config) {
    return Mono.empty();
  }

  /**
   * One-time setup for the plugin instance.
   *
   * @param config the plugin configuration
   * @return a Mono that completes when initialization is done
   */
  default Mono<Void> initialize(Map<String, Object> config) {
    return Mono.empty();
  }
}
