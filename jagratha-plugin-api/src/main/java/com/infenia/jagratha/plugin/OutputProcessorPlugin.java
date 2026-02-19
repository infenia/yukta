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

/** Interface for processing task execution output. */
public interface OutputProcessorPlugin {
  /**
   * Get the name of the plugin.
   *
   * @return the plugin name
   */
  String getName();

  /**
   * Process the output of a task.
   *
   * @param input the input data for processing
   * @return the result of the processing
   */
  ProcessorResult process(ProcessorInput input);

  /**
   * Validate the plugin configuration.
   *
   * @param config the configuration to validate
   * @return the validation result
   */
  default ValidationResult validateConfig(Map<String, Object> config) {
    return ValidationResult.success();
  }

  /** Input data for output processing. */
  record ProcessorInput(
      String sessionId,
      String projectRoot,
      String module,
      String taskName,
      String taskOutput,
      String resultsDir,
      Map<String, Object> config) {
    /**
     * Compact constructor to ensure configuration is immutable.
     *
     * @param sessionId the session identifier
     * @param projectRoot the project root
     * @param module the module identifier
     * @param taskName the task name
     * @param taskOutput the task output
     * @param resultsDir the results directory
     * @param config the processor configuration
     */
    public ProcessorInput {
      config = config != null ? Map.copyOf(config) : Map.of();
    }
  }

  /** Result of output processing. */
  record ProcessorResult(String status, String output, String artifactPath) {}
}
