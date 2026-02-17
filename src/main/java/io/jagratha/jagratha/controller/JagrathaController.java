package io.jagratha.jagratha.controller;

import io.jagratha.jagratha.config.JagrathaConfigService;
import io.jagratha.jagratha.model.ConfigRequest;
import io.jagratha.jagratha.model.FileRequest;
import io.jagratha.jagratha.model.TaskRequest;
import io.jagratha.jagratha.model.TaskResponse;
import io.jagratha.jagratha.service.JagrathaService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for Jagratha operations. Provides endpoints for file management and task
 * execution.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class JagrathaController {

  private final JagrathaService service;
  private final JagrathaConfigService configService;

  private static final String SUCCESS_STATUS = "SUCCESS";

  /**
   * Log a file path for a session.
   *
   * @param request the file request containing path and sessionId
   * @return response entity with success or error message
   */
  @PostMapping("/files")
  public Mono<ResponseEntity<String>> saveFile(@Valid @RequestBody final FileRequest request) {
    return service
        .saveFile(request.path(), request.sessionId())
        .thenReturn(ResponseEntity.ok("File path logged successfully"))
        .onErrorResume(
            IllegalArgumentException.class,
            e -> Mono.just(ResponseEntity.badRequest().body(e.getMessage())))
        .onErrorResume(
            Exception.class,
            e ->
                Mono.just(
                    ResponseEntity.internalServerError()
                        .body("Failed to log file path: " + e.getMessage())));
  }

  /**
   * Run quality checks on the external project and return the results.
   *
   * @param request the task request containing sessionId
   * @return response entity with task status and output
   */
  @PostMapping("/tasks/complete")
  public Mono<ResponseEntity<TaskResponse>> completeTask(
      @Valid @RequestBody final TaskRequest request) {
    return service
        .runQualityChecks(request.sessionId())
        .map(
            response -> {
              if (SUCCESS_STATUS.equals(response.status())) {
                return ResponseEntity.ok(response);
              } else {
                return ResponseEntity.status(500).body(response);
              }
            });
  }

  /**
   * List logs for a session.
   *
   * @param sessionId the session identifier
   * @return list of log filenames
   */
  @GetMapping("/logs/{sessionId}")
  public Mono<List<String>> listLogs(@PathVariable final String sessionId) {
    return service.listLogs(sessionId);
  }

  /**
   * Get content of a specific log file.
   *
   * @param sessionId the session identifier
   * @param filename the log filename
   * @return log content
   */
  @GetMapping("/logs/{sessionId}/{filename}")
  public Mono<ResponseEntity<String>> getLogContent(
      @PathVariable final String sessionId, @PathVariable final String filename) {
    return service
        .getLogContent(sessionId, filename)
        .map(ResponseEntity::ok)
        .onErrorResume(e -> Mono.just(ResponseEntity.notFound().build()));
  }

  /**
   * Update configuration at runtime.
   *
   * @param request the config request containing new configuration values
   * @return response entity with success message
   */
  @PostMapping("/config")
  public ResponseEntity<String> updateConfig(@RequestBody final ConfigRequest request) {
    if (request.projectPath() != null) {
      configService.setProjectPath(request.projectPath());
    }
    if (request.pluginName() != null) {
      configService.setPluginName(request.pluginName());
    }
    if (request.pluginConfig() != null) {
      configService.setPluginConfig(request.pluginConfig());
    }
    if (request.tasks() != null) {
      configService.setTasks(request.tasks());
    }
    if (request.executionTimeout() != null) {
      configService.setExecutionTimeout(request.executionTimeout());
    }
    if (request.modifiedFile() != null) {
      configService.setFileLogDir(request.modifiedFile());
    }
    if (request.results() != null) {
      configService.setResultLogDir(request.results());
    }
    return ResponseEntity.ok("Configuration updated successfully");
  }
}
