package com.infenia.jagratha.plugin;

import java.util.Map;

/** Interface for AI tool plugins. */
public interface AiPlugin {
  /**
   * Get the name of the plugin.
   *
   * @return the plugin name
   */
  String getName();

  /**
   * Execute the AI tool with a prompt.
   *
   * @param prompt the user prompt
   * @param config plugin-specific configuration
   * @return the AI response
   */
  String execute(String prompt, Map<String, Object> config);

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
