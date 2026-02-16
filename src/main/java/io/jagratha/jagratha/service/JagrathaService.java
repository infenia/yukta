package io.jagratha.jagratha.service;

import io.jagratha.jagratha.config.JagrathaConfig;
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

  private final JagrathaConfig config;

  private static final String FAILURE_STATUS = "FAILURE";

  /**
   * Save a file to the external project.
   *
   * @param relativePath the relative path of the file
   * @param content the content to write to the file
   * @return Mono that completes when the file is saved
   */
  public Mono<Void> saveFile(@NotBlank final String relativePath, @NotBlank final String content) {
    return Mono.defer(
            () -> {
              final String projectRoot = getProjectRoot();
              if (projectRoot == null || projectRoot.isEmpty()) {
                return Mono.error(
                    new IllegalStateException("External project path is not configured"));
              }
              final Path fullPath = Paths.get(projectRoot).resolve(relativePath).normalize();

              // Security check: ensure the path is within the project root
              if (!fullPath.startsWith(Paths.get(projectRoot).normalize())) {
                return Mono.error(
                    new IllegalArgumentException("Invalid file path: " + relativePath));
              }

              return Mono.fromRunnable(
                      () -> {
                        try {
                          Files.createDirectories(fullPath.getParent());
                          Files.writeString(fullPath, content);
                          log.info("Saved file to {}", fullPath);
                        } catch (IOException e) {
                          log.error("Failed to save file", e);
                          throw new UncheckedIoException(
                              "Failed to save file: " + e.getMessage(), e);
                        }
                      })
                  .subscribeOn(Schedulers.boundedElastic());
            })
        .then();
  }

  /**
   * Run quality checks on the external project.
   *
   * @return Mono containing the task response with status and output
   */
  public Mono<TaskResponse> runQualityChecks() {
    return Mono.fromCallable(this::executeQualityChecks).subscribeOn(Schedulers.boundedElastic());
  }

  private TaskResponse executeQualityChecks() {
    final String projectRoot = getProjectRoot();
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

    return response;
  }

  @SuppressWarnings("PMD.DoNotUseThreads")
  private TaskResponse executeGradleChecks(final File projectDir) {
    final List<String> command = buildGradleCommand();
    final String projectRoot = projectDir.getAbsolutePath();

    if (log.isInfoEnabled()) {
      log.info("Running quality checks in {}: {}", projectRoot, String.join(" ", command));
    }

    TaskResponse response;
    try {
      final ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.directory(projectDir);
      processBuilder.redirectErrorStream(true);
      final Process process = processBuilder.start();

      final String output = readProcessOutput(process);
      final boolean finished = process.waitFor(10, TimeUnit.MINUTES);

      if (finished) {
        final int exitCode = process.exitValue();
        log.info("Quality checks finished with exit code {}", exitCode);
        response = new TaskResponse(exitCode == 0 ? "SUCCESS" : FAILURE_STATUS, output);
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

  private String getProjectRoot() {
    return config.externalProject() != null ? config.externalProject().path() : null;
  }

  private List<String> buildGradleCommand() {
    final String gradlePath =
        config.externalProject() != null ? config.externalProject().gradlePath() : null;
    final List<String> command = new ArrayList<>();
    command.add(gradlePath != null ? gradlePath : "./gradlew");
    command.add("spotlessApply");
    command.add("spotlessCheck");
    command.add("checkstyleMain");
    command.add("test");
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
