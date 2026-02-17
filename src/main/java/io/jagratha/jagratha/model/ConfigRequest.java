package io.jagratha.jagratha.model;

import java.util.List;

/**
 * Request object for configuration updates.
 *
 * @param sessionId the session identifier (optional)
 * @param projectPath the project path
 * @param gradlePath the gradle path
 * @param tasks the list of tasks
 * @param executionTimeout the execution timeout
 * @param modifiedFile the modified file log directory
 * @param results the results log directory
 */
public record ConfigRequest(
    String sessionId,
    String projectPath,
    String gradlePath,
    List<String> tasks,
    Long executionTimeout,
    String modifiedFile,
    String results) {

  /** Compact constructor to ensure tasks list is immutable. */
  public ConfigRequest {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
  }
}
