package com.infenia.jagratha.model;

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
public record WorkflowConfig(
    @NotBlank(message = "Task name is required") String task,
    @Valid ProcessorStepConfig processor,
    @Valid AiStepConfig aiStep) {

  /**
   * Configuration for an output processor step.
   *
   * @param name the processor plugin name
   * @param config the processor configuration
   */
  public record ProcessorStepConfig(
      @NotBlank(message = "Processor name is required") String name,
      @NotNull(message = "Processor config is required") Map<String, Object> config) {
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
  public record AiStepConfig(
      @NotBlank(message = "AI step name is required") String name,
      @NotNull(message = "AI step config is required") Map<String, Object> config) {
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
