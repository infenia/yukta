package com.infenia.jagratha.config;

import com.infenia.jagratha.model.WorkflowConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service for managing app configuration with runtime overrides. Provides a way to update
 * configuration via API while maintaining precedence: API > Command Line > Environment >
 * application.yaml.
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.DataClass")
public class AppConfigService {

  private static final String DEFAULT_BASE_DIR = System.getProperty("user.home") + "/.jagratha";
  private static final long DEFAULT_TIMEOUT = 300L;

  private final AppConfig staticConfig;

  private final AtomicReference<String> projectPath = new AtomicReference<>();
  private final AtomicReference<String> pluginName = new AtomicReference<>();
  private final AtomicReference<Map<String, Object>> pluginConfig = new AtomicReference<>();
  private final AtomicReference<List<String>> tasks = new AtomicReference<>();
  private final AtomicReference<List<WorkflowConfig>> workflows = new AtomicReference<>();
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
   * Get the plugin name.
   *
   * @return the plugin name
   */
  public String getPluginName() {
    final String override = pluginName.get();
    final String result;
    if (override != null) {
      result = override;
    } else {
      result = staticConfig.pluginName();
    }
    return result;
  }

  /**
   * Set the plugin name override.
   *
   * @param name the plugin name
   */
  public void setPluginName(final String name) {
    pluginName.set(name);
  }

  /**
   * Get the plugin configuration.
   *
   * @return the plugin configuration map
   */
  public Map<String, Object> getPluginConfig() {
    final Map<String, Object> override = pluginConfig.get();
    final Map<String, Object> result;
    if (override != null) {
      result = override;
    } else if (staticConfig.pluginConfig() != null) {
      result = staticConfig.pluginConfig();
    } else {
      result = Map.of();
    }
    return result;
  }

  /**
   * Set the plugin configuration override.
   *
   * @param config the configuration map
   */
  public void setPluginConfig(final Map<String, Object> config) {
    if (config != null) {
      pluginConfig.set(Map.copyOf(config));
    } else {
      pluginConfig.set(Map.of());
    }
  }

  /**
   * Get the list of tasks.
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
   * Set the tasks override.
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
   * Get the list of workflows.
   *
   * @return the list of workflows
   */
  public List<WorkflowConfig> getWorkflows() {
    final List<WorkflowConfig> override = workflows.get();
    final List<WorkflowConfig> result;
    if (override != null && !override.isEmpty()) {
      result = override;
    } else {
      result = staticConfig.workflows();
    }
    return result;
  }

  /**
   * Set the workflows override.
   *
   * @param workflowsList the list of workflows
   */
  public void setWorkflows(final List<WorkflowConfig> workflowsList) {
    if (workflowsList != null) {
      workflows.set(List.copyOf(workflowsList));
    } else {
      workflows.set(List.of());
    }
  }

  /**
   * Get the execution timeout in seconds.
   *
   * @return the timeout
   */
  public Long getExecutionTimeout() {
    final Long override = executionTimeout.get();
    final long result;
    if (override != null) {
      result = override;
    } else if (staticConfig.executionTimeout() != null) {
      result = staticConfig.executionTimeout();
    } else {
      result = DEFAULT_TIMEOUT;
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
    } else if (staticConfig.logs() != null && staticConfig.logs().modifiedFile() != null) {
      result = staticConfig.logs().modifiedFile();
    } else {
      result = DEFAULT_BASE_DIR + "/modified-files";
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
   * Get the results log directory.
   *
   * @return the log directory
   */
  public String getResultLogDir() {
    final String override = resultLogDir.get();
    final String result;
    if (override != null) {
      result = override;
    } else if (staticConfig.logs() != null && staticConfig.logs().results() != null) {
      result = staticConfig.logs().results();
    } else {
      result = DEFAULT_BASE_DIR + "/results";
    }
    return result;
  }

  /**
   * Set the results log directory override.
   *
   * @param dir the log directory
   */
  public void setResultLogDir(final String dir) {
    resultLogDir.set(dir);
  }
}
