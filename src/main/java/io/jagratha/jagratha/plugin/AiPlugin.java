package io.jagratha.jagratha.plugin;

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
}
