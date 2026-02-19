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

import java.util.List;
import java.util.Map;

/** Interface for Jagratha plugins. */
public interface JagrathaPlugin {
  /**
   * Get the name of the plugin.
   *
   * @return the plugin name
   */
  String getName();

  /**
   * Identify the module for a given file path.
   *
   * @param projectRoot the root path of the project
   * @param relativePath the relative path of the file
   * @return the module identifier (e.g., ":module-name") or empty string for root
   */
  String identifyModule(String projectRoot, String relativePath);

  /**
   * Build the command to execute a single task for a module.
   *
   * @param module the module identifier
   * @param task the task name
   * @param pluginConfig plugin-specific configuration
   * @return the command as a list of strings
   */
  List<String> buildTaskCommand(String module, String task, Map<String, Object> pluginConfig);

  /**
   * Validate the plugin configuration.
   *
   * @param config the configuration to validate
   * @return the validation result
   */
  default ValidationResult validateConfig(Map<String, Object> config) {
    return ValidationResult.success();
  }
}
