package com.infenia.jagratha.model;

import java.util.Map;

/**
 * Configuration for a workflow step.
 *
 * @param task the task name
 * @param processor the output processor configuration
 * @param ai the AI plugin configuration
 */
public record WorkflowConfig(String task, ProcessorStepConfig processor, AiStepConfig aiStep) {

  /**
   * Configuration for an output processor step.
   *
   * @param name the processor plugin name
   * @param config the processor configuration
   */
  public record ProcessorStepConfig(String name, Map<String, Object> config) {
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
  public record AiStepConfig(String name, Map<String, Object> config) {
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
