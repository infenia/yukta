package io.jagratha.jagratha.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Jagratha.
 *
 * @param externalProject configuration for the external project
 * @param pluginName the name of the active plugin
 * @param pluginConfig plugin-specific configuration
 * @param tasks list of tasks to run
 * @param executionTimeout timeout for execution in seconds
 * @param logs configuration for logging directories
 */
@ConfigurationProperties(prefix = "jagratha")
public record JagrathaConfig(
    ExternalProject externalProject,
    String pluginName,
    Map<String, Object> pluginConfig,
    List<String> tasks,
    Long executionTimeout,
    Logs logs) {

  /** Compact constructor to ensure tasks list and maps are immutable. */
  public JagrathaConfig {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
    pluginConfig = pluginConfig != null ? Map.copyOf(pluginConfig) : Map.of();
  }

  /**
   * Configuration for the external project.
   *
   * @param path path to the external project
   */
  public record ExternalProject(String path) {}

  /**
   * Configuration for logging directories.
   *
   * @param modifiedFile directory for session-based modified files logs
   * @param results directory for session-based results logs
   */
  public record Logs(String modifiedFile, String results) {}
}
