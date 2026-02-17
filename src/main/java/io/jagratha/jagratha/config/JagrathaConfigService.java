package io.jagratha.jagratha.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service for managing Jagratha configuration with runtime overrides. Provides a way to update
 * configuration via API while maintaining precedence: API > Command Line > Environment >
 * application.yaml.
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.DataClass")
public class JagrathaConfigService {

  private final JagrathaConfig staticConfig;

  private final AtomicReference<String> projectPath = new AtomicReference<>();
  private final AtomicReference<String> gradlePath = new AtomicReference<>();
  private final AtomicReference<List<String>> tasks = new AtomicReference<>();
  private final AtomicReference<Long> executionTimeout = new AtomicReference<>();
  private final AtomicReference<String> fileLogDir = new AtomicReference<>();
  private final AtomicReference<String> resultLogDir = new AtomicReference<>();

  /**
   * Get the external project path.
   *
   * @return the project path
   */
  public String getProjectPath() {
    final String override = projectPath.get();
    final String result;
    if (override != null) {
      result = override;
    } else if (staticConfig.externalProject() != null) {
      result = staticConfig.externalProject().path();
    } else {
      result = "";
    }
    return result;
  }

  /**
   * Set the external project path override.
   *
   * @param path the project path
   */
  public void setProjectPath(final String path) {
    projectPath.set(path);
  }

  /**
   * Get the Gradle path.
   *
   * @return the Gradle path
   */
  public String getGradlePath() {
    final String override = gradlePath.get();
    final String result;
    if (override != null) {
      result = override;
    } else if (staticConfig.externalProject() != null) {
      result = staticConfig.externalProject().gradlePath();
    } else {
      result = "";
    }
    return result;
  }

  /**
   * Set the Gradle path override.
   *
   * @param path the Gradle path
   */
  public void setGradlePath(final String path) {
    gradlePath.set(path);
  }

  /**
   * Get the list of Gradle tasks.
   *
   * @return the list of tasks
   */
  public List<String> getTasks() {
    final List<String> override = tasks.get();
    final List<String> result;
    if (override != null && !override.isEmpty()) {
      result = override;
    } else {
      result = staticConfig.tasks();
    }
    return result;
  }

  /**
   * Set the Gradle tasks override.
   *
   * @param tasksList the list of tasks
   */
  public void setTasks(final List<String> tasksList) {
    if (tasksList != null) {
      tasks.set(List.copyOf(tasksList));
    } else {
      tasks.set(List.of());
    }
  }

  /**
   * Get the execution timeout in seconds.
   *
   * @return the timeout
   */
  public Long getExecutionTimeout() {
    final Long override = executionTimeout.get();
    final Long result;
    if (override != null) {
      result = override;
    } else {
      result = staticConfig.executionTimeout();
    }
    return result;
  }

  /**
   * Set the execution timeout override.
   *
   * @param timeout the timeout in seconds
   */
  public void setExecutionTimeout(final Long timeout) {
    executionTimeout.set(timeout);
  }

  /**
   * Get the modified files log directory.
   *
   * @return the log directory
   */
  public String getFileLogDir() {
    final String override = fileLogDir.get();
    final String result;
    if (override != null) {
      result = override;
    } else if (staticConfig.logs() != null) {
      result = staticConfig.logs().modifiedFilesDir();
    } else {
      result = "";
    }
    return result;
  }

  /**
   * Set the modified files log directory override.
   *
   * @param dir the log directory
   */
  public void setFileLogDir(final String dir) {
    fileLogDir.set(dir);
  }

  /**
   * Get the Gradle results log directory.
   *
   * @return the log directory
   */
  public String getResultLogDir() {
    final String override = resultLogDir.get();
    final String result;
    if (override != null) {
      result = override;
    } else if (staticConfig.logs() != null) {
      result = staticConfig.logs().gradleResultsDir();
    } else {
      result = "";
    }
    return result;
  }

  /**
   * Set the Gradle results log directory override.
   *
   * @param dir the log directory
   */
  public void setResultLogDir(final String dir) {
    resultLogDir.set(dir);
  }
}
