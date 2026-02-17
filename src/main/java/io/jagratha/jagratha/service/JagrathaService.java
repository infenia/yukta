package io.jagratha.jagratha.service;

import io.jagratha.jagratha.config.JagrathaConfigService;
import io.jagratha.jagratha.model.TaskResponse;
import jakarta.validation.constraints.NotBlank;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for managing files and running quality checks on external projects. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class JagrathaService {

  private final JagrathaConfigService configService;

  private static final String FAILURE_STATUS = "FAILURE";
  private static final String SUCCESS_STATUS = "SUCCESS";

  /**
   * Log a file path to a session-specific file.
   *
   * @param path the file path to log
   * @param sessionId the session identifier
   * @return Mono that completes when the file path is logged
   */
  public Mono<Void> saveFile(@NotBlank final String path, @NotBlank final String sessionId) {
    return Mono.fromRunnable(
            () -> {
              final String logsDir = configService.getFileLogDir();
              if (logsDir == null || logsDir.isEmpty()) {
                throw new IllegalStateException("Modified files log directory is not configured");
              }

              try {
                final Path dirPath = Paths.get(logsDir);
                Files.createDirectories(dirPath);
                final Path logFile = dirPath.resolve(sessionId + ".log");
                Files.writeString(
                    logFile,
                    path + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
                log.info("Logged file path {} for session {}", path, sessionId);
              } catch (IOException e) {
                log.error("Failed to log file path", e);
                throw new UncheckedIoException("Failed to log file path: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  /**
   * Run quality checks on the external project and log results.
   *
   * @param sessionId the session identifier
   * @return Mono containing the task response
   */
  public Mono<TaskResponse> runQualityChecks(@NotBlank final String sessionId) {
    return Mono.fromCallable(() -> executeQualityChecks(sessionId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private TaskResponse executeQualityChecks(final String sessionId) {
    final String projectRoot = configService.getProjectPath();
    final TaskResponse response;

    if (projectRoot == null || projectRoot.isEmpty()) {
      response = new TaskResponse(FAILURE_STATUS, "External project path is not configured");
    } else {
      final File projectDir = new File(projectRoot);
      if (projectDir.exists() && projectDir.isDirectory()) {
        response = executeGradleChecks(projectDir);
      } else {
        response =
            new TaskResponse(
                FAILURE_STATUS, "External project directory does not exist: " + projectRoot);
      }
    }

    logResults(sessionId, response);
    return response;
  }

  private void logResults(final String sessionId, final TaskResponse response) {
    final String logsDir = configService.getResultLogDir();
    if (logsDir == null || logsDir.isEmpty()) {
      log.warn("Gradle results log directory is not configured, skipping result logging");
      return;
    }

    try {
      final Path dirPath = Paths.get(logsDir);
      Files.createDirectories(dirPath);
      final Path logFile = dirPath.resolve(sessionId + ".log");
      final String content = "Status: " + response.status() + "\n\nOutput:\n" + response.output();
      Files.writeString(
          logFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
      log.info("Logged Gradle results for session {}", sessionId);
    } catch (IOException e) {
      log.error("Failed to log Gradle results", e);
    }
  }

  private TaskResponse executeGradleChecks(final File projectDir) {
    final List<String> command = buildGradleCommand();
    final String projectRoot = projectDir.getAbsolutePath();

    if (log.isInfoEnabled()) {
      log.info("Running quality checks in {}: {}", projectRoot, String.join(" ", command));
    }

    return tryExecuteGradleChecks(command, projectDir);
  }

  @SuppressWarnings("PMD.DoNotUseThreads")
  private TaskResponse tryExecuteGradleChecks(final List<String> command, final File projectDir) {
    TaskResponse response;
    try {
      final ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.directory(projectDir);
      processBuilder.redirectErrorStream(true);
      final Process process = processBuilder.start();

      final String output = readProcessOutput(process);
      final Long timeout = configService.getExecutionTimeout();
      final boolean finished = process.waitFor(timeout != null ? timeout : 600, TimeUnit.SECONDS);

      if (finished) {
        final int exitCode = process.exitValue();
        log.info("Quality checks finished with exit code {}", exitCode);
        response = new TaskResponse(exitCode == 0 ? SUCCESS_STATUS : FAILURE_STATUS, output);
      } else {
        process.destroyForcibly();
        response =
            new TaskResponse(FAILURE_STATUS, "Timeout while running quality checks.\n" + output);
      }

    } catch (IOException e) {
      log.error("Error executing Gradle", e);
      response = new TaskResponse(FAILURE_STATUS, "Error executing Gradle: " + e.getMessage());
    } catch (InterruptedException e) {
      log.error("Quality checks interrupted", e);
      Thread.currentThread().interrupt();
      response = new TaskResponse(FAILURE_STATUS, "Execution interrupted: " + e.getMessage());
    }
    return response;
  }

  private List<String> buildGradleCommand() {
    final String gradlePath = configService.getGradlePath();
    final List<String> command = new ArrayList<>();
    command.add(gradlePath != null ? gradlePath : "./gradlew");

    final List<String> tasks = configService.getTasks();
    if (tasks != null && !tasks.isEmpty()) {
      command.addAll(tasks);
    } else {
      command.add("spotlessApply");
      command.add("spotlessCheck");
      command.add("checkstyleMain");
      command.add("test");
    }
    return command;
  }

  private String readProcessOutput(final Process process) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.joining("\n"));
    }
  }

  private static final class UncheckedIoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private UncheckedIoException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
