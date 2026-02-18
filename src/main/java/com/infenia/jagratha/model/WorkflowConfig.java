package com.infenia.jagratha.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    @Schema(description = "The name of the build task to run", example = "checkstyleMain")
        @NotBlank(message = "Task name is required")
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
          @NotBlank(message = "Processor name is required")
          String name,
      @Schema(description = "Configuration options for the processor")
          @NotNull(message = "Processor config is required")
          Map<String, Object> config) {
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
      @Schema(description = "The name of the AI plugin", example = "qwen-code")
          @NotBlank(message = "AI step name is required")
          String name,
      @Schema(description = "Configuration options for the AI plugin (e.g., prompt templates)")
          @NotNull(message = "AI step config is required")
          Map<String, Object> config) {
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
