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
 * @param fileLogDir the file log directory
 * @param resultLogDir the result log directory
 */
public record ConfigRequest(
    String sessionId,
    String projectPath,
    String gradlePath,
    List<String> tasks,
    Long executionTimeout,
    String fileLogDir,
    String resultLogDir) {

  /** Compact constructor to ensure tasks list is immutable. */
  public ConfigRequest {
    tasks = tasks != null ? List.copyOf(tasks) : List.of();
  }
}
