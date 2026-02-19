package com.infenia.jagratha.config;

import com.infenia.jagratha.model.WorkflowConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Service for managing app configuration with runtime overrides. Provides a way to update
 * configuration via API while maintaining precedence: Session Override > Command Line > Environment
 * > application.yaml.
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
public class AppConfigService {

  private static final String DEFAULT_BASE_DIR = System.getProperty("user.home") + "/.jagratha";
  private static final long DEFAULT_TIMEOUT = 300L;

  private final AppConfig staticConfig;

  private final Map<String, String> projectPaths = new ConcurrentHashMap<>();
  private final Map<String, String> pluginNames = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> pluginConfigs = new ConcurrentHashMap<>();
  private final Map<String, List<String>> tasksMap = new ConcurrentHashMap<>();
  private final Map<String, List<WorkflowConfig>> workflowsMap = new ConcurrentHashMap<>();
  private final Map<String, Long> executionTimeouts = new ConcurrentHashMap<>();
  private final Map<String, String> fileLogDirs = new ConcurrentHashMap<>();
  private final Map<String, String> resultLogDirs = new ConcurrentHashMap<>();

  /**
   * Get the external project path for a session.
   *
   * @param sessionId the session identifier
   * @return the project path
   */
  public String getProjectPath(final String sessionId) {
    final String override = sessionId != null ? projectPaths.get(sessionId) : null;
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
   * Set the external project path override for a session.
   *
   * @param sessionId the session identifier
   * @param path the project path
   */
  public void setProjectPath(final String sessionId, final String path) {
    if (sessionId != null && path != null) {
      projectPaths.put(sessionId, path);
    }
  }

  /**
   * Get the plugin name for a session.
   *
   * @param sessionId the session identifier
   * @return the plugin name
   */
  public String getPluginName(final String sessionId) {
    final String override = sessionId != null ? pluginNames.get(sessionId) : null;
    final String result;
    if (override != null) {
      result = override;
    } else {
      result = staticConfig.pluginName();
    }
    return result;
  }

  /**
   * Set the plugin name override for a session.
   *
   * @param sessionId the session identifier
   * @param name the plugin name
   */
  public void setPluginName(final String sessionId, final String name) {
    if (sessionId != null && name != null) {
      pluginNames.put(sessionId, name);
    }
  }

  /**
   * Get the plugin configuration for a session.
   *
   * @param sessionId the session identifier
   * @return the plugin configuration map
   */
  public Map<String, Object> getPluginConfig(final String sessionId) {
    final Map<String, Object> override = sessionId != null ? pluginConfigs.get(sessionId) : null;
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
   * Set the plugin configuration override for a session.
   *
   * @param sessionId the session identifier
   * @param config the configuration map
   */
  public void setPluginConfig(final String sessionId, final Map<String, Object> config) {
    if (sessionId != null) {
      if (config != null) {
        pluginConfigs.put(sessionId, Map.copyOf(config));
      } else {
        pluginConfigs.remove(sessionId);
      }
    }
  }

  /**
   * Get the list of tasks for a session.
   *
   * @param sessionId the session identifier
   * @return the list of tasks
   */
  public List<String> getTasks(final String sessionId) {
    final List<String> override = sessionId != null ? tasksMap.get(sessionId) : null;
    final List<String> result;
    if (override != null && !override.isEmpty()) {
      result = override;
    } else {
      result = staticConfig.tasks();
    }
    return result;
  }

  /**
   * Set the tasks override for a session.
   *
   * @param sessionId the session identifier
   * @param tasksList the list of tasks
   */
  public void setTasks(final String sessionId, final List<String> tasksList) {
    if (sessionId != null) {
      if (tasksList != null) {
        tasksMap.put(sessionId, List.copyOf(tasksList));
      } else {
        tasksMap.remove(sessionId);
      }
    }
  }

  /**
   * Get the list of workflows for a session.
   *
   * @param sessionId the session identifier
   * @return the list of workflows
   */
  public List<WorkflowConfig> getWorkflows(final String sessionId) {
    final List<WorkflowConfig> override = sessionId != null ? workflowsMap.get(sessionId) : null;
    final List<WorkflowConfig> result;
    if (override != null && !override.isEmpty()) {
      result = override;
    } else {
      result = staticConfig.workflows();
    }
    return result;
  }

  /**
   * Set the workflows override for a session.
   *
   * @param sessionId the session identifier
   * @param workflowsList the list of workflows
   */
  public void setWorkflows(final String sessionId, final List<WorkflowConfig> workflowsList) {
    if (sessionId != null) {
      if (workflowsList != null) {
        workflowsMap.put(sessionId, List.copyOf(workflowsList));
      } else {
        workflowsMap.remove(sessionId);
      }
    }
  }

  /**
   * Get the execution timeout in seconds for a session.
   *
   * @param sessionId the session identifier
   * @return the timeout
   */
  public Long getExecutionTimeout(final String sessionId) {
    final Long override = sessionId != null ? executionTimeouts.get(sessionId) : null;
    final Long result;
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
   * Set the execution timeout override for a session.
   *
   * @param sessionId the session identifier
   * @param timeout the timeout in seconds
   */
  public void setExecutionTimeout(final String sessionId, final Long timeout) {
    if (sessionId != null && timeout != null) {
      executionTimeouts.put(sessionId, timeout);
    }
  }

  /**
   * Get the modified files log directory for a session.
   *
   * @param sessionId the session identifier
   * @return the log directory
   */
  public String getFileLogDir(final String sessionId) {
    final String override = sessionId != null ? fileLogDirs.get(sessionId) : null;
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
   * Set the modified files log directory override for a session.
   *
   * @param sessionId the session identifier
   * @param dir the log directory
   */
  public void setFileLogDir(final String sessionId, final String dir) {
    if (sessionId != null && dir != null) {
      fileLogDirs.put(sessionId, dir);
    }
  }

  /**
   * Get the results log directory for a session.
   *
   * @param sessionId the session identifier
   * @return the log directory
   */
  public String getResultLogDir(final String sessionId) {
    final String override = sessionId != null ? resultLogDirs.get(sessionId) : null;
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
   * Set the results log directory override for a session.
   *
   * @param sessionId the session identifier
   * @param dir the log directory
   */
  public void setResultLogDir(final String sessionId, final String dir) {
    if (sessionId != null && dir != null) {
      resultLogDirs.put(sessionId, dir);
    }
  }

  /**
   * Get all configurations for a session as a map.
   *
   * @param sessionId the session identifier
   * @return map of configurations
   */
  public Map<String, Object> getAllConfigs(final String sessionId) {
    final Map<String, Object> configs = new java.util.LinkedHashMap<>();
    configs.put("projectPath", getProjectPath(sessionId));
    configs.put("pluginName", getPluginName(sessionId));
    configs.put("pluginConfig", getPluginConfig(sessionId));
    configs.put("tasks", getTasks(sessionId));
    configs.put("workflows", getWorkflows(sessionId));
    configs.put("executionTimeout", getExecutionTimeout(sessionId));
    configs.put("fileLogDir", getFileLogDir(sessionId));
    configs.put("resultLogDir", getResultLogDir(sessionId));
    return configs;
  }

  /**
   * Get all active session IDs currently in memory.
   *
   * @return set of session IDs
   */
  public java.util.Set<String> getActiveSessionIds() {
    final java.util.Set<String> active = new java.util.HashSet<>();
    active.addAll(projectPaths.keySet());
    active.addAll(pluginNames.keySet());
    active.addAll(pluginConfigs.keySet());
    active.addAll(tasksMap.keySet());
    active.addAll(workflowsMap.keySet());
    active.addAll(executionTimeouts.keySet());
    active.addAll(fileLogDirs.keySet());
    active.addAll(resultLogDirs.keySet());
    return active;
  }

  /**
   * Check if a session is active in memory.
   *
   * @param sessionId the session identifier
   * @return true if active
   */
  public boolean isActive(final String sessionId) {
    return sessionId != null && getActiveSessionIds().contains(sessionId);
  }
}
