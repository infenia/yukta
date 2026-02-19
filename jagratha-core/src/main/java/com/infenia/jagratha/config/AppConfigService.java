package com.infenia.jagratha.config;

import com.infenia.jagratha.model.PluginRegistration;
import com.infenia.jagratha.model.WorkflowConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Service for managing app configuration with runtime overrides. All configuration is session-based
 * and updated via API.
 */
@Service
@SuppressWarnings("PMD.UseConcurrentHashMap")
public class AppConfigService {

  private static final String DEFAULT_BASE_DIR = System.getProperty("user.home") + "/.jagratha";
  private static final String DEFAULT_FILE_LOG = DEFAULT_BASE_DIR + "/modified-files";
  private static final String DEFAULT_RES_LOG = DEFAULT_BASE_DIR + "/results";
  private static final long DEFAULT_TIMEOUT = 300L;
  private static final List<String> DEFAULT_TASKS =
      List.of("spotlessApply", "spotlessCheck", "checkstyleMain", "test");

  private final Map<String, String> projectPaths = new ConcurrentHashMap<>();
  private final Map<String, List<PluginRegistration>> pluginsMap = new ConcurrentHashMap<>();
  private final Map<String, List<WorkflowConfig>> workflowsMap = new ConcurrentHashMap<>();

  /** Public constructor. */
  public AppConfigService() {
    super();
  }

  /**
   * Get the external project path for a session.
   *
   * @param sessionId the session identifier
   * @return the project path
   */
  public String getProjectPath(final String sessionId) {
    final String result = sessionId != null ? projectPaths.get(sessionId) : null;
    return result != null ? result : "";
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
   * Get all registered plugins for a session.
   *
   * @param sessionId the session identifier
   * @return the list of plugins
   */
  public List<PluginRegistration> getPlugins(final String sessionId) {
    final List<PluginRegistration> result = sessionId != null ? pluginsMap.get(sessionId) : null;
    return result != null ? result : List.of();
  }

  /**
   * Set the registered plugins for a session.
   *
   * @param sessionId the session identifier
   * @param plugins the list of plugins
   */
  public void setPlugins(final String sessionId, final List<PluginRegistration> plugins) {
    if (sessionId != null && plugins != null) {
      pluginsMap.put(sessionId, List.copyOf(plugins));
    }
  }

  /**
   * Get the plugin name for a session. For now, returns the first plugin's name.
   *
   * @param sessionId the session identifier
   * @return the plugin name
   */
  public String getPluginName(final String sessionId) {
    final List<PluginRegistration> plugins = getPlugins(sessionId);
    return plugins.isEmpty() ? "" : plugins.get(0).name();
  }

  /**
   * Get the plugin configuration for a session. For now, returns the first plugin's configuration.
   *
   * @param sessionId the session identifier
   * @return the plugin configuration map
   */
  public Map<String, Object> getPluginConfig(final String sessionId) {
    final List<PluginRegistration> plugins = getPlugins(sessionId);
    return plugins.isEmpty() ? Map.of() : plugins.get(0).pluginConfig();
  }

  /**
   * Get the list of tasks for a session. Defaults to a standard list if not set.
   *
   * @param sessionId the session identifier
   * @return the list of tasks
   */
  public List<String> getTasks(final String sessionId) {
    return DEFAULT_TASKS;
  }

  /**
   * Get the list of workflows for a session.
   *
   * @param sessionId the session identifier
   * @return the list of workflows
   */
  public List<WorkflowConfig> getWorkflows(final String sessionId) {
    final List<WorkflowConfig> override = sessionId != null ? workflowsMap.get(sessionId) : null;
    return override != null ? override : List.of();
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
    return DEFAULT_TIMEOUT;
  }

  /**
   * Get the modified files log directory for a session.
   *
   * @param sessionId the session identifier
   * @return the log directory
   */
  public String getFileLogDir(final String sessionId) {
    return DEFAULT_FILE_LOG;
  }

  /**
   * Get the results log directory for a session.
   *
   * @param sessionId the session identifier
   * @return the log directory
   */
  public String getResultLogDir(final String sessionId) {
    return DEFAULT_RES_LOG;
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
    configs.put("plugins", getPlugins(sessionId));
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
    active.addAll(pluginsMap.keySet());
    active.addAll(workflowsMap.keySet());
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
