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
package com.infenia.jagratha.model;

import com.infenia.jagratha.validation.AiStepName;
import com.infenia.jagratha.validation.ConfigKey;
import com.infenia.jagratha.validation.ProcessorName;
import com.infenia.jagratha.validation.TaskName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Configuration for a workflow step.
 *
 * @param task the task name
 * @param processor the output processor configuration
 * @param aiStep the AI plugin configuration
 */
@Schema(description = "Configuration for a workflow step, chaining build tasks with AI feedback")
public record WorkflowConfig(
    @Schema(description = "The name of the build task to run", example = "checkstyleMain") @TaskName
        String task,
    @Schema(description = "Configuration for processing the task output") @Valid
        ProcessorStepConfig processor,
    @Schema(description = "Configuration for an AI agent step to analyze task output") @Valid
        AiStepConfig aiStep) {

  /**
   * Configuration for an output processor step.
   *
   * @param name the processor plugin name
   * @param config the processor configuration
   */
  @Schema(description = "Configuration for an output processor step")
  public record ProcessorStepConfig(
      @Schema(description = "The name of the processor plugin", example = "checkstyle-xml")
          @ProcessorName
          String name,
      @Schema(description = "Configuration options for the processor")
          @NotNull(message = "Processor config is required")
          Map<@ConfigKey String, @NotNull(message = "Processor config value cannot be null") Object>
              config) {
    /**
     * Compact constructor to ensure configuration is immutable.
     *
     * @param name the processor plugin name
     * @param config the processor configuration
     */
    public ProcessorStepConfig {
      config = config != null ? Map.copyOf(config) : Map.of();
    }
  }

  /**
   * Configuration for an AI plugin step.
   *
   * @param name the AI plugin name
   * @param config the AI configuration
   */
  @Schema(description = "Configuration for an AI plugin step")
  public record AiStepConfig(
      @Schema(description = "The name of the AI plugin", example = "qwen-code") @AiStepName
          String name,
      @Schema(description = "Configuration options for the AI plugin (e.g., prompt templates)")
          @NotNull(message = "AI step config is required")
          Map<@ConfigKey String, @NotNull(message = "AI config value cannot be null") Object>
              config) {
    /**
     * Compact constructor to ensure configuration is immutable.
     *
     * @param name the AI plugin name
     * @param config the AI configuration
     */
    public AiStepConfig {
      config = config != null ? Map.copyOf(config) : Map.of();
    }
  }
}
