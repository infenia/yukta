package io.jagratha.jagratha.plugin;

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
}
