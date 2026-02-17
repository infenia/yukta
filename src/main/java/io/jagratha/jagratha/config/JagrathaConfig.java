package io.jagratha.jagratha.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Jagratha.
 *
 * @param externalProject configuration for the external project
 * @param tasks list of gradle tasks to run
 * @param executionTimeout timeout for gradle execution in seconds
 * @param logs configuration for logging directories
 */
@ConfigurationProperties(prefix = "jagratha")
public record JagrathaConfig(
    ExternalProject externalProject, List<String> tasks, Long executionTimeout, Logs logs) {

  /** Compact constructor to ensure tasks list is immutable. */
  public JagrathaConfig {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
  }

  /**
   * Configuration for the external project.
   *
   * @param path path to the external project
   * @param gradlePath path to the Gradle executable
   */
  public record ExternalProject(String path, String gradlePath) {}

  /**
   * Configuration for logging directories.
   *
   * @param modifiedFile directory for session-based modified files logs
   * @param results directory for session-based gradle results logs
   */
  public record Logs(String modifiedFile, String results) {}
}
