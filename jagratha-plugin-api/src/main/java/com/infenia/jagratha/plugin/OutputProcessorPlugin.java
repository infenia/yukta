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
