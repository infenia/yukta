package io.jagratha.jagratha.service;

import io.jagratha.jagratha.config.JagrathaConfig;
import io.jagratha.jagratha.model.TaskResponse;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class JagrathaService {

  private final JagrathaConfig config;

  private static final String FAILURE_STATUS = "FAILURE";

  public Mono<Void> saveFile(String relativePath, String content) {
    return Mono.fromRunnable(
            () -> {
              try {
                String projectRoot = getProjectRoot();
                if (projectRoot == null || projectRoot.isEmpty()) {
                  throw new IllegalStateException("External project path is not configured");
                }
                Path fullPath = Paths.get(projectRoot).resolve(relativePath).normalize();

                // Security check: ensure the path is within the project root
                if (!fullPath.startsWith(Paths.get(projectRoot).normalize())) {
                  throw new IllegalArgumentException("Invalid file path: " + relativePath);
                }

                Files.createDirectories(fullPath.getParent());
                Files.writeString(fullPath, content);
                log.info("Saved file to {}", fullPath);
              } catch (IOException e) {
                log.error("Failed to save file", e);
                throw new UncheckedFileIOException("Failed to save file: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  public Mono<TaskResponse> runQualityChecks() {
    return Mono.fromCallable(this::executeQualityChecks).subscribeOn(Schedulers.boundedElastic());
  }

  private TaskResponse executeQualityChecks() {
    String projectRoot = getProjectRoot();
    if (projectRoot == null || projectRoot.isEmpty()) {
      return new TaskResponse(FAILURE_STATUS, "External project path is not configured");
    }

    File projectDir = new File(projectRoot);
    if (!projectDir.exists() || !projectDir.isDirectory()) {
      return new TaskResponse(
          FAILURE_STATUS, "External project directory does not exist: " + projectRoot);
    }

    List<String> command = buildGradleCommand();
    log.info("Running quality checks in {}: {}", projectRoot, String.join(" ", command));

    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.directory(projectDir);
      pb.redirectErrorStream(true);
      Process process = pb.start();

      String output = readProcessOutput(process);
      boolean finished = process.waitFor(10, TimeUnit.MINUTES);

      if (!finished) {
        process.destroyForcibly();
        return new TaskResponse(FAILURE_STATUS, "Timeout while running quality checks.\n" + output);
      }

      int exitCode = process.exitValue();
      log.info("Quality checks finished with exit code {}", exitCode);
      return new TaskResponse(exitCode == 0 ? "SUCCESS" : FAILURE_STATUS, output);

    } catch (IOException e) {
      log.error("Error executing Gradle", e);
      return new TaskResponse(FAILURE_STATUS, "Error executing Gradle: " + e.getMessage());
    } catch (InterruptedException e) {
      log.error("Quality checks interrupted", e);
      Thread.currentThread().interrupt();
      return new TaskResponse(FAILURE_STATUS, "Execution interrupted: " + e.getMessage());
    }
  }

  private String getProjectRoot() {
    return config.externalProject() != null ? config.externalProject().path() : null;
  }

  private List<String> buildGradleCommand() {
    String gradlePath =
        config.externalProject() != null ? config.externalProject().gradlePath() : null;
    List<String> command = new ArrayList<>();
    command.add(gradlePath != null ? gradlePath : "./gradlew");
    command.add("spotlessApply");
    command.add("spotlessCheck");
    command.add("checkstyleMain");
    command.add("test");
    return command;
  }

  private String readProcessOutput(Process process) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.joining("\n"));
    }
  }

  private static class UncheckedFileIOException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UncheckedFileIOException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
