package com.infenia.jagratha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.PluginRegistration;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.plugin.AiPlugin;
import com.infenia.jagratha.plugin.JagrathaPlugin;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import com.infenia.jagratha.plugin.ValidationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for managing session lifecycle and configuration. */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("PMD.OnlyOneReturn")
public class SessionService {

  private final AppConfigService configService;
  private final ObjectMapper objectMapper;
  private final List<JagrathaPlugin> plugins;
  private final List<OutputProcessorPlugin> processorPlugins;
  private final List<AiPlugin> aiPlugins;

  private static final String SESS_ID_PATTERN = "^(?!.*\\.\\.)[^/\\\\]*$";

  /**
   * Apply configuration overrides for a session.
   *
   * @param data the configuration data
   * @return Mono that completes when config is applied
   */
  public Mono<Void> applyConfigOverrides(final AppConfigData data) {
    return Mono.fromRunnable(
            () -> {
              validatePlugins(data.plugins());

              if (data.projectPath() != null) {
                configService.setProjectPath(data.sessionId(), data.projectPath());
              }
              if (data.plugins() != null && !data.plugins().isEmpty()) {
                configService.setPlugins(data.sessionId(), data.plugins());
              }
              if (data.workflows() != null && !data.workflows().isEmpty()) {
                configService.setWorkflows(data.sessionId(), data.workflows());
              }
            })
        .then(saveConfigToDisk(data.sessionId()))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void validatePlugins(final List<PluginRegistration> registrations) {
    if (registrations == null) {
      return;
    }
    for (final PluginRegistration reg : registrations) {
      final Object plugin = findPlugin(reg.name());
      if (plugin == null) {
        throw new IllegalArgumentException("Plugin not installed in core system: " + reg.name());
      }
      final ValidationResult result = validatePluginConfig(plugin, reg.pluginConfig());
      if (!result.valid()) {
        throw new IllegalArgumentException(
            "Validation failed for plugin " + reg.name() + ": " + result.message());
      }
    }
  }

  /* default */ Object findPlugin(final String name) {
    Object result = plugins.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    if (result == null) {
      result =
          processorPlugins.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    }
    if (result == null) {
      result = aiPlugins.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    }
    return result;
  }

  private ValidationResult validatePluginConfig(
      final Object plugin, final Map<String, Object> config) {
    if (plugin instanceof JagrathaPlugin jagrathaPlugin) {
      return jagrathaPlugin.validateConfig(config);
    }
    if (plugin instanceof OutputProcessorPlugin processorPlugin) {
      return processorPlugin.validateConfig(config);
    }
    if (plugin instanceof AiPlugin aiPlugin) {
      return aiPlugin.validateConfig(config);
    }
    return ValidationResult.success();
  }

  /**
   * Save configuration to disk.
   *
   * @param sessionId the session identifier
   * @return Mono that completes when config is saved
   */
  public Mono<Void> saveConfigToDisk(final String sessionId) {
    return Mono.fromRunnable(
            () -> {
              final String resultsDir = configService.getResultLogDir(sessionId);
              if (resultsDir == null || resultsDir.isEmpty()) {
                return;
              }
              try {
                final Path dirPath = Path.of(resultsDir).resolve(sessionId);
                Files.createDirectories(dirPath);
                final Path configFile = dirPath.resolve("config.json");
                final Map<String, Object> configs = configService.getAllConfigs(sessionId);
                Files.writeString(configFile, objectMapper.writeValueAsString(configs));
              } catch (IOException e) {
                log.error("Failed to save config to disk for session {}", sessionId, e);
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  /**
   * Get all session IDs that have logs or configurations on disk.
   *
   * @return Flux of session IDs
   */
  public Flux<String> getAllSessionsOnDisk() {
    return Flux.defer(
            () -> {
              final Set<String> sessions = new TreeSet<>();
              final String resultsDir = configService.getResultLogDir(null);
              final String fileLogDir = configService.getFileLogDir(null);

              addSessionIds(sessions, resultsDir);
              addSessionIds(sessions, fileLogDir);

              return Flux.fromIterable(sessions);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private void addSessionIds(final Set<String> sessions, final String baseDir) {
    if (baseDir == null || baseDir.isEmpty()) {
      return;
    }
    try {
      final Path path = Path.of(baseDir);
      if (Files.exists(path) && Files.isDirectory(path)) {
        try (Stream<Path> dirs = Files.list(path)) {
          dirs.filter(Files::isDirectory)
              .map(p -> p.getFileName().toString())
              .filter(name -> name.matches(SESS_ID_PATTERN))
              .forEach(sessions::add);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to list sessions from directory: {}", baseDir, e);
    }
  }

  /**
   * Get all active session IDs.
   *
   * @return Flux of active session IDs
   */
  public Flux<String> getActiveSessions() {
    return Flux.fromIterable(configService.getActiveSessionIds());
  }

  /**
   * Get all history session IDs (on disk but not active).
   *
   * @return Flux of history session IDs
   */
  public Flux<String> getHistorySessions() {
    return getAllSessionsOnDisk()
        .collectList()
        .flatMapMany(
            allOnDisk -> {
              final Set<String> active = configService.getActiveSessionIds();
              return Flux.fromIterable(
                  allOnDisk.stream().filter(s -> !active.contains(s)).toList());
            });
  }

  /**
   * Get configuration for a session, from memory or disk.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of configurations
   */
  @SuppressWarnings("unchecked")
  public Mono<Map<String, Object>> getSessionConfig(final String sessionId) {
    return Mono.fromCallable(
            () -> {
              if (configService.isActive(sessionId)) {
                return configService.getAllConfigs(sessionId);
              }

              final String resultsDir = configService.getResultLogDir(sessionId);
              final Path configFile = Path.of(resultsDir).resolve(sessionId).resolve("config.json");
              if (Files.exists(configFile)) {
                try {
                  return (Map<String, Object>)
                      objectMapper.readValue(
                          Files.readString(configFile, StandardCharsets.UTF_8), Map.class);
                } catch (IOException e) {
                  log.warn("Failed to read config.json for session {}", sessionId, e);
                }
              }
              return configService.getAllConfigs(sessionId);
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /**
   * Get workflows for a session, from memory or disk.
   *
   * @param sessionId the session identifier
   * @return Mono containing list of workflows
   */
  public Mono<List<WorkflowConfig>> getSessionWorkflows(final String sessionId) {
    return Mono.defer(
        () -> {
          if (configService.isActive(sessionId)) {
            return Mono.just(configService.getWorkflows(sessionId));
          }

          return getSessionConfig(sessionId)
              .map(
                  config -> {
                    final Object workflows = config.get("workflows");
                    if (workflows instanceof List) {
                      try {
                        final String json = objectMapper.writeValueAsString(workflows);
                        return objectMapper.readValue(
                            json,
                            objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, WorkflowConfig.class));
                      } catch (IOException e) {
                        log.warn(
                            "Failed to parse workflows from disk for session {}", sessionId, e);
                      }
                    }
                    return configService.getWorkflows(sessionId);
                  });
        });
  }
}
