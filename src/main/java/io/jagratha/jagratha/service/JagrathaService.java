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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
@SuppressWarnings({"PMD.TooManyMethods", "PMD.OnlyOneReturn", "PMD.GodClass"})
public class JagrathaService {

  private final JagrathaConfigService configService;

  private static final String FAILURE_STATUS = "FAILURE";
  private static final String SUCCESS_STATUS = "SUCCESS";
  private static final String PENDING_STATUS = "PENDING";
  private static final int SB_CAPACITY = 2048;

  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  private ReentrantLock getLock(final String sessionId) {
    return locks.computeIfAbsent(sessionId, k -> new ReentrantLock());
  }

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
                throw new IllegalStateException(
                    "Modified files log directory is not configured. Please use the /api/config "
                        + "endpoint to initialize the project configuration.");
              }

              final ReentrantLock lock = getLock(sessionId);
              lock.lock();
              try {
                final Path logFile = Paths.get(logsDir).resolve(sessionId + ".log");
                Files.createDirectories(logFile.getParent());

                final Map<String, String> files = readLogFile(logFile);
                files.put(normalizePath(path), PENDING_STATUS);
                writeLogFile(logFile, files);

                if (log.isInfoEnabled()) {
                  log.info("Logged file path {} for session {}", path, sessionId);
                }
              } catch (IOException e) {
                log.error("Failed to log file path", e);
                throw new UncheckedIoException("Failed to log file path: " + e.getMessage(), e);
              } finally {
                lock.unlock();
              }
            })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  private Map<String, String> readLogFile(final Path logFile) throws IOException {
    if (!Files.exists(logFile)) {
      return new LinkedHashMap<>();
    }
    try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
      return lines
          .filter(line -> !line.isBlank())
          .map(this::parseLogLine)
          .collect(
              Collectors.toMap(
                  Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v2, LinkedHashMap::new));
    }
  }

  private Map.Entry<String, String> parseLogLine(final String line) {
    final int sepIdx = line.lastIndexOf('|');
    if (sepIdx == -1) {
      return new AbstractMap.SimpleEntry<>(line, PENDING_STATUS);
    }
    return new AbstractMap.SimpleEntry<>(line.substring(0, sepIdx), line.substring(sepIdx + 1));
  }

  private void writeLogFile(final Path logFile, final Map<String, String> files)
      throws IOException {
    final List<String> lines =
        files.entrySet().stream()
            .map(entry -> entry.getKey() + "|" + entry.getValue())
            .collect(Collectors.toList());
    Files.write(logFile, lines, StandardCharsets.UTF_8);
  }

  private String normalizePath(final String path) {
    final String root = configService.getProjectPath();
    if (root == null || root.isEmpty()) {
      return path;
    }
    return tryNormalize(path, root);
  }

  private String tryNormalize(final String path, final String root) {
    String result = path;
    try {
      final Path rootPath = Paths.get(root).toAbsolutePath().normalize();
      final Path filePath = Paths.get(path).toAbsolutePath().normalize();
      if (filePath.startsWith(rootPath)) {
        result = rootPath.relativize(filePath).toString();
      }
    } catch (InvalidPathException e) {
      log.warn("Failed to normalize path: {}", path, e);
    }
    return result;
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
    final String logsDir = configService.getFileLogDir();

    if (projectRoot == null || projectRoot.isEmpty()) {
      return respondAndLog(sessionId, FAILURE_STATUS, "External project path not configured.");
    }
    if (logsDir == null || logsDir.isEmpty()) {
      return respondAndLog(sessionId, FAILURE_STATUS, "File log directory not configured.");
    }

    final File projectDir = new File(projectRoot);
    if (!projectDir.exists() || !projectDir.isDirectory()) {
      return respondAndLog(sessionId, FAILURE_STATUS, "Project directory does not exist.");
    }

    final TaskResponse response = processSessionLogs(sessionId, projectRoot, projectDir, logsDir);
    logResults(sessionId, response);
    return response;
  }

  private TaskResponse respondAndLog(
      final String sessionId, final String status, final String msg) {
    final TaskResponse response = new TaskResponse(status, msg);
    logResults(sessionId, response);
    return response;
  }

  private TaskResponse processSessionLogs(
      final String sessionId,
      final String projectRoot,
      final File projectDir,
      final String logsDir) {
    final ReentrantLock lock = getLock(sessionId);
    lock.lock();
    try {
      final Path logFile = Paths.get(logsDir).resolve(sessionId + ".log");
      final Map<String, String> files = readLogFile(logFile);
      final Map<String, List<String>> pendingByModule =
          files.entrySet().stream()
              .filter(entry -> !SUCCESS_STATUS.equals(entry.getValue()))
              .collect(
                  Collectors.groupingBy(
                      entry -> identifyModule(projectRoot, entry.getKey()),
                      LinkedHashMap::new,
                      Collectors.mapping(Map.Entry::getKey, Collectors.toList())));

      if (pendingByModule.isEmpty()) {
        return new TaskResponse(SUCCESS_STATUS, "No pending changes to process.");
      }
      final TaskResponse response = runChecksForModules(projectDir, pendingByModule, files);
      writeLogFile(logFile, files);
      return response;
    } catch (IOException e) {
      log.error("Failed to manage session logs", e);
      return new TaskResponse(FAILURE_STATUS, "Error managing logs: " + e.getMessage());
    } finally {
      lock.unlock();
    }
  }

  private TaskResponse runChecksForModules(
      final File projectDir,
      final Map<String, List<String>> pendingByModule,
      final Map<String, String> allFiles) {

    String overallStatus = SUCCESS_STATUS;
    final StringBuilder combinedOutput = new StringBuilder(SB_CAPACITY);

    for (final Map.Entry<String, List<String>> entry : pendingByModule.entrySet()) {
      final String module = entry.getKey();
      final List<String> moduleFiles = entry.getValue();
      final List<String> command = buildGradleCommand(module);

      if (log.isInfoEnabled()) {
        log.info("Running quality checks for module {}: {}", module, String.join(" ", command));
      }

      combinedOutput
          .append("--- Module: ")
          .append(module.isEmpty() ? "root" : module)
          .append(" ---\n");
      final TaskResponse res = tryExecuteGradleChecks(command, projectDir);
      combinedOutput.append(res.output()).append("\n\n");

      if (FAILURE_STATUS.equals(res.status())) {
        overallStatus = FAILURE_STATUS;
      }
      for (final String file : moduleFiles) {
        allFiles.put(file, res.status());
      }
    }
    return new TaskResponse(overallStatus, combinedOutput.toString());
  }

  private String identifyModule(final String projectRoot, final String relativePath) {
    String result = "";
    try {
      final Path rootPath = Paths.get(projectRoot).toAbsolutePath().normalize();
      final Path fileAbsPath = rootPath.resolve(relativePath).toAbsolutePath().normalize();

      Path current = fileAbsPath.getParent();
      while (current != null && current.startsWith(rootPath)) {
        if (Files.exists(current.resolve("build.gradle"))
            || Files.exists(current.resolve("build.gradle.kts"))) {
          final Path relPath = rootPath.relativize(current);
          final String modulePath = relPath.toString();
          if (!modulePath.isEmpty()) {
            result = ":" + modulePath.replace(File.separator, ":");
          }
          break;
        }
        current = current.getParent();
      }
    } catch (InvalidPathException e) {
      log.warn("Failed to identify module for path: {}", relativePath, e);
    }
    return result;
  }

  private void logResults(final String sessionId, final TaskResponse response) {
    final String logsDir = configService.getResultLogDir();
    if (logsDir != null && !logsDir.isEmpty()) {
      try {
        final Path dirPath = Paths.get(logsDir);
        Files.createDirectories(dirPath);
        final Path logFile = dirPath.resolve(sessionId + ".log");
        final String content = "Status: " + response.status() + "\n\nOutput:\n" + response.output();
        Files.writeString(
            logFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        if (log.isInfoEnabled()) {
          log.info("Logged Gradle results for session {}", sessionId);
        }
      } catch (IOException e) {
        log.error("Failed to log Gradle results", e);
      }
    }
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
        if (log.isInfoEnabled()) {
          log.info("Quality checks finished with exit code {}", exitCode);
        }
        response = new TaskResponse(exitCode == 0 ? SUCCESS_STATUS : FAILURE_STATUS, output);
      } else {
        process.destroyForcibly();
        response = new TaskResponse(FAILURE_STATUS, "Timeout while running checks.\n" + output);
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

  private List<String> buildGradleCommand(final String module) {
    final String gradlePath = configService.getGradlePath();
    final List<String> command = new ArrayList<>();
    command.add(gradlePath != null ? gradlePath : "./gradlew");

    List<String> tasks = configService.getTasks();
    if (tasks == null || tasks.isEmpty()) {
      tasks = List.of("spotlessApply", "spotlessCheck", "checkstyleMain", "test");
    }

    for (final String task : tasks) {
      if (module.isEmpty()) {
        command.add(task);
      } else if (task.startsWith(":")) {
        command.add(task);
      } else {
        command.add(module + ":" + task);
      }
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
