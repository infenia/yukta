package com.infenia.jagratha.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.plugin.AiPlugin;
import com.infenia.jagratha.plugin.JagrathaPlugin;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;

/** Service for managing files and running quality checks on external projects. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.OnlyOneReturn",
  "PMD.UseConcurrentHashMap",
  "PMD.CouplingBetweenObjects",
  "PMD.CyclomaticComplexity"
})
public class AppService {

  private final AppConfigService configService;
  private final ObjectMapper objectMapper;
  private final List<JagrathaPlugin> plugins;
  private final List<OutputProcessorPlugin> processorPlugins;
  private final List<AiPlugin> aiPlugins;

  private static final String FAILURE_STATUS = "FAILURE";
  private static final String SUCCESS_STATUS = "SUCCESS";
  private static final String PENDING_STATUS = "PENDING";
  private static final int SB_CAPACITY = 2048;
  private static final String SESS_ID_PATTERN = "^(?!.*\\.\\.)[^/\\\\]*$";
  private static final String SESS_ID_MSG = "Invalid session ID format";

  private final Map<String, ReentrantLock> locks = new java.util.concurrent.ConcurrentHashMap<>();

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
  public Mono<Void> saveFile(
      @NotBlank final String path,
      @NotBlank @Pattern(regexp = SESS_ID_PATTERN, message = SESS_ID_MSG) final String sessionId) {
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
                final Path sessionDir = Path.of(logsDir).resolve(sessionId);
                Files.createDirectories(sessionDir);
                final Path logFile = sessionDir.resolve(sessionId + ".log");

                final Map<String, String> files = readLogFile(logFile);
                files.put(normalizePath(path), PENDING_STATUS);
                writeLogFile(logFile, files);

                if (log.isInfoEnabled()) {
                  log.info("Logged file path {} for session {}", path, sessionId);
                }
              } catch (IOException e) {
                if (log.isErrorEnabled()) {
                  log.error("Failed to log file path", e);
                }
                throw new UncheckedIoException("Failed to log file path: " + e.getMessage(), e);
              } finally {
                lock.unlock();
              }
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .then();
  }

  private Map<String, String> readLogFile(final Path logFile) throws IOException {
    if (!Files.exists(logFile)) {
      return new java.util.LinkedHashMap<>();
    }
    try (Stream<String> lines = Files.lines(logFile, StandardCharsets.UTF_8)) {
      final Map<String, String> result = new java.util.LinkedHashMap<>();
      lines
          .filter(line -> !line.isBlank())
          .forEach(
              line -> {
                try {
                  final LogEntry entry = objectMapper.readValue(line, LogEntry.class);
                  result.put(entry.getPath(), entry.getStatus());
                } catch (JsonProcessingException e) {
                  if (log.isWarnEnabled()) {
                    log.warn("Failed to parse log line: {}", line, e);
                  }
                }
              });
      return result;
    }
  }

  private void writeLogFile(final Path logFile, final Map<String, String> files)
      throws IOException {
    final List<String> lines = new ArrayList<>();
    for (final Map.Entry<String, String> entry : files.entrySet()) {
      try {
        lines.add(objectMapper.writeValueAsString(new LogEntry(entry.getKey(), entry.getValue())));
      } catch (JsonProcessingException e) {
        if (log.isErrorEnabled()) {
          log.error("Failed to serialize log entry for file: {}", entry.getKey(), e);
        }
      }
    }
    Files.write(logFile, lines, StandardCharsets.UTF_8);
  }

  @lombok.Getter
  @lombok.Setter
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  /* default */ static class LogEntry {
    private String path;
    private String status;
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
      final Path rootPath = Path.of(root).toAbsolutePath().normalize();
      final Path filePath = Path.of(path).toAbsolutePath().normalize();
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
  public Mono<TaskResponse> runQualityChecks(
      @NotBlank @Pattern(regexp = SESS_ID_PATTERN, message = SESS_ID_MSG) final String sessionId) {
    return Mono.fromCallable(() -> executeQualityChecks(sessionId))
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  /**
   * List all log files for a given session.
   *
   * @param sessionId the session identifier
   * @return Mono containing a list of log filenames
   */
  public Mono<List<String>> listLogs(
      @NotBlank @Pattern(regexp = SESS_ID_PATTERN, message = SESS_ID_MSG) final String sessionId) {
    return Mono.fromCallable(
            () -> {
              final List<String> logFiles = new ArrayList<>();
              final String resultsDir = configService.getResultLogDir();
              final String fileLogDir = configService.getFileLogDir();

              addLogsFromDir(logFiles, resultsDir, sessionId);
              addLogsFromDir(logFiles, fileLogDir, sessionId);

              return logFiles.stream().distinct().sorted().toList();
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  private void addLogsFromDir(
      final List<String> logFiles, final String baseDir, final String sessionId) {
    if (baseDir == null || baseDir.isEmpty()) {
      return;
    }
    try {
      final Path sessionDir = Path.of(baseDir).resolve(sessionId);
      if (Files.exists(sessionDir) && Files.isDirectory(sessionDir)) {
        try (Stream<Path> files = Files.list(sessionDir)) {
          files
              .filter(Files::isRegularFile)
              .map(path -> path.getFileName().toString())
              .forEach(logFiles::add);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to list logs from directory: {}", baseDir, e);
    }
  }

  /**
   * Get the content of a specific log file.
   *
   * @param sessionId the session identifier
   * @param fileName the log filename
   * @return Mono containing the log content
   */
  public Mono<String> getLogContent(
      @NotBlank @Pattern(regexp = SESS_ID_PATTERN, message = SESS_ID_MSG) final String sessionId,
      @NotBlank @Pattern(regexp = "^(?!.*\\.\\.)[^/\\\\]*$", message = "Invalid file name format")
          final String fileName) {
    return Mono.fromCallable(() -> fetchLogContent(sessionId, fileName))
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
  }

  private String fetchLogContent(final String sessionId, final String fileName) throws IOException {
    final String resultsDir = configService.getResultLogDir();
    final String fileLogDir = configService.getFileLogDir();

    Path logFile = findLogFile(resultsDir, sessionId, fileName);
    if (logFile == null) {
      logFile = findLogFile(fileLogDir, sessionId, fileName);
    }

    if (logFile == null || !Files.exists(logFile)) {
      throw new IOException("Log file not found: " + fileName);
    }
    return Files.readString(logFile, StandardCharsets.UTF_8);
  }

  private Path findLogFile(final String baseDir, final String sessionId, final String fileName) {
    if (baseDir != null && !baseDir.isEmpty()) {
      final Path path = Path.of(baseDir).resolve(sessionId).resolve(fileName);
      if (Files.exists(path)) {
        return path;
      }
    }
    return null;
  }

  private TaskResponse executeQualityChecks(final String sessionId) {
    final String projectRoot = configService.getProjectPath();
    final String logsDir = configService.getFileLogDir();
    final String pluginName = configService.getPluginName();

    if (projectRoot == null || projectRoot.isEmpty()) {
      return respondAndLog(sessionId, FAILURE_STATUS, "External project path not configured.");
    }
    if (logsDir == null || logsDir.isEmpty()) {
      return respondAndLog(sessionId, FAILURE_STATUS, "File log directory not configured.");
    }
    if (pluginName == null || pluginName.isEmpty()) {
      return respondAndLog(
          sessionId,
          FAILURE_STATUS,
          "No plugin name configured. Please use the /api/config endpoint to initialize the "
              + "project configuration.");
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

  private JagrathaPlugin getActivePlugin() {
    final String pluginName = configService.getPluginName();
    if (pluginName == null || pluginName.isEmpty()) {
      throw new IllegalStateException(
          "No plugin name configured. Please use the /api/config endpoint to initialize the project"
              + " configuration.");
    }
    return plugins.stream()
        .filter(p -> pluginName.equals(p.getName()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Plugin not found: " + pluginName));
  }

  private TaskResponse processSessionLogs(
      final String sessionId,
      final String projectRoot,
      final File projectDir,
      final String logsDir) {
    final ReentrantLock lock = getLock(sessionId);
    lock.lock();
    try {
      final JagrathaPlugin plugin = getActivePlugin();
      final Path logFile = Path.of(logsDir).resolve(sessionId).resolve(sessionId + ".log");
      final Map<String, String> files = readLogFile(logFile);
      final Map<String, List<String>> pendingByModule =
          files.entrySet().stream()
              .filter(entry -> !SUCCESS_STATUS.equals(entry.getValue()))
              .collect(
                  java.util.stream.Collectors.groupingBy(
                      entry -> plugin.identifyModule(projectRoot, entry.getKey()),
                      java.util.LinkedHashMap::new,
                      java.util.stream.Collectors.mapping(
                          Map.Entry::getKey, java.util.stream.Collectors.toList())));

      if (pendingByModule.isEmpty()) {
        return new TaskResponse(SUCCESS_STATUS, "No pending changes to process.");
      }
      final TaskResponse response =
          runChecksForModules(projectDir, pendingByModule, files, sessionId);
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
      final Map<String, String> allFiles,
      final String sessionId) {

    String overallStatus = SUCCESS_STATUS;
    final StringBuilder combinedOutput = new StringBuilder(SB_CAPACITY);
    final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    for (final Map.Entry<String, List<String>> entry : pendingByModule.entrySet()) {
      final String module = entry.getKey();
      final TaskResponse moduleRes =
          executeModuleTasks(projectDir, sessionId, combinedOutput, formatter, module);

      if (FAILURE_STATUS.equals(moduleRes.status())) {
        overallStatus = FAILURE_STATUS;
      }
      for (final String file : entry.getValue()) {
        allFiles.put(file, moduleRes.status());
      }
      if (FAILURE_STATUS.equals(overallStatus)) {
        break;
      }
    }
    return new TaskResponse(overallStatus, combinedOutput.toString());
  }

  private TaskResponse executeModuleTasks(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final DateTimeFormatter formatter,
      final String module) {

    combinedOutput
        .append("--- Module: ")
        .append(module.isEmpty() ? "root" : module)
        .append(" ---\n");

    final List<WorkflowConfig> workflows = configService.getWorkflows();
    if (workflows != null && !workflows.isEmpty()) {
      return runWorkflows(projectDir, sessionId, combinedOutput, formatter, module, workflows);
    }
    return runSimpleTasks(projectDir, sessionId, combinedOutput, formatter, module);
  }

  private TaskResponse runWorkflows(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final DateTimeFormatter formatter,
      final String module,
      final List<WorkflowConfig> workflows) {
    for (final WorkflowConfig workflow : workflows) {
      final TaskResponse res =
          executeWorkflow(projectDir, sessionId, combinedOutput, formatter, module, workflow);
      if (FAILURE_STATUS.equals(res.status())) {
        return res;
      }
    }
    return new TaskResponse(SUCCESS_STATUS, "");
  }

  private TaskResponse runSimpleTasks(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final DateTimeFormatter formatter,
      final String module) {
    List<String> tasks = configService.getTasks();
    if (tasks == null || tasks.isEmpty()) {
      tasks = List.of("spotlessApply", "spotlessCheck", "checkstyleMain", "test");
    }

    for (final String task : tasks) {
      final TaskResponse res =
          executeSingleTask(
              projectDir, sessionId, formatter, module, task, configService.getPluginConfig());
      combinedOutput
          .append("Task: ")
          .append(task)
          .append(" - ")
          .append(res.status())
          .append('\n')
          .append(res.output())
          .append("\n\n");

      if (FAILURE_STATUS.equals(res.status())) {
        return res;
      }
    }
    return new TaskResponse(SUCCESS_STATUS, "");
  }

  private TaskResponse executeWorkflow(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final DateTimeFormatter formatter,
      final String module,
      final WorkflowConfig workflow) {

    final TaskResponse taskRes =
        executeSingleTask(
            projectDir,
            sessionId,
            formatter,
            module,
            workflow.task(),
            configService.getPluginConfig());

    combinedOutput
        .append("Task: ")
        .append(workflow.task())
        .append(" - ")
        .append(taskRes.status())
        .append('\n')
        .append(taskRes.output())
        .append('\n');

    if (FAILURE_STATUS.equals(taskRes.status()) && workflow.processor() == null) {
      return new TaskResponse(FAILURE_STATUS, combinedOutput.toString());
    }

    String artifactPath = null;
    if (workflow.processor() != null) {
      final OutputProcessorPlugin processor = findProcessor(workflow.processor().name());
      final OutputProcessorPlugin.ProcessorResult procRes =
          processor.process(
              new OutputProcessorPlugin.ProcessorInput(
                  sessionId,
                  configService.getProjectPath(),
                  module,
                  workflow.task(),
                  taskRes.output(),
                  configService.getResultLogDir(),
                  workflow.processor().config()));

      artifactPath = procRes.artifactPath();
      combinedOutput
          .append("Processor: ")
          .append(workflow.processor().name())
          .append(" - ")
          .append(procRes.status())
          .append('\n')
          .append(procRes.output())
          .append('\n');

      if (FAILURE_STATUS.equals(procRes.status())) {
        return new TaskResponse(FAILURE_STATUS, combinedOutput.toString());
      }
    }

    if (workflow.aiStep() != null) {
      final AiPlugin aiPlugin = findAiPlugin(workflow.aiStep().name());
      final String prompt =
          constructPrompt(workflow.aiStep().config(), taskRes.output(), artifactPath);
      final String aiResponse = aiPlugin.execute(prompt, workflow.aiStep().config());

      combinedOutput
          .append("AI (")
          .append(workflow.aiStep().name())
          .append("):\n")
          .append(aiResponse)
          .append('\n');

      saveAiLog(
          sessionId, module, workflow.task(), workflow.aiStep().name(), formatter, aiResponse);
    }

    combinedOutput.append('\n');
    return new TaskResponse(SUCCESS_STATUS, "");
  }

  private OutputProcessorPlugin findProcessor(final String name) {
    return processorPlugins.stream()
        .filter(p -> p.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Processor not found: " + name));
  }

  private AiPlugin findAiPlugin(final String name) {
    return aiPlugins.stream()
        .filter(p -> p.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("AI plugin not found: " + name));
  }

  private String constructPrompt(
      final Map<String, Object> config, final String taskOutput, final String artifactPath) {
    String template = (String) config.get("promptTemplate");
    if (template == null || template.isEmpty()) {
      template = "Task Output:\n{{taskOutput}}\n\nProcessor Output:\n{{processorOutput}}";
    }

    String result = template.replace("{{taskOutput}}", taskOutput);
    if (artifactPath != null && !artifactPath.isEmpty()) {
      try {
        final String artifactContent =
            Files.readString(Path.of(artifactPath), StandardCharsets.UTF_8);
        result = result.replace("{{processorOutput}}", artifactContent);
      } catch (IOException e) {
        log.warn("Failed to read artifact for prompt: {}", artifactPath, e);
        result =
            result.replace(
                "{{processorOutput}}", "Error reading processor artifact: " + e.getMessage());
      }
    } else {
      result = result.replace("{{processorOutput}}", "No processor output available.");
    }
    return result;
  }

  private void saveAiLog(
      final String sessionId,
      final String module,
      final String task,
      final String aiName,
      final DateTimeFormatter formatter,
      final String response) {
    final String logsDir = configService.getResultLogDir();
    if (logsDir != null && !logsDir.isEmpty()) {
      try {
        final String timestamp = LocalDateTime.now().format(formatter);
        final String logFileName =
            String.format(
                "%s-%s-%s-%s.log",
                module.isEmpty() ? "root" : module.replace(":", "-").substring(1),
                task,
                aiName,
                timestamp);
        final Path dirPath = Path.of(logsDir).resolve(sessionId);
        Files.createDirectories(dirPath);
        Files.writeString(dirPath.resolve(logFileName), response);
      } catch (IOException e) {
        log.error("Failed to log AI response", e);
      }
    }
  }

  private TaskResponse executeSingleTask(
      final File projectDir,
      final String sessionId,
      final DateTimeFormatter formatter,
      final String module,
      final String task,
      final Map<String, Object> pluginConfig) {
    final List<String> command = getActivePlugin().buildTaskCommand(module, task, pluginConfig);
    final String timestamp = LocalDateTime.now().format(formatter);
    final String logFileName =
        String.format(
            "%s-%s-%s.log",
            module.isEmpty() ? "root" : module.replace(":", "-").substring(1), task, timestamp);

    if (log.isInfoEnabled()) {
      log.info("Running quality check: {}", String.join(" ", command));
    }

    final TaskResponse res = tryExecuteChecks(command, projectDir);
    saveTaskLog(sessionId, logFileName, res);
    return res;
  }

  private void saveTaskLog(final String sessionId, final String fileName, final TaskResponse res) {
    final String logsDir = configService.getResultLogDir();
    if (logsDir != null && !logsDir.isEmpty()) {
      try {
        final Path dirPath = Path.of(logsDir).resolve(sessionId);
        Files.createDirectories(dirPath);
        final Path logFile = dirPath.resolve(fileName);
        final String content = "Status: " + res.status() + "\n\nOutput:\n" + res.output();
        Files.writeString(logFile, content);
      } catch (IOException e) {
        if (log.isErrorEnabled()) {
          log.error("Failed to log task result", e);
        }
      }
    }
  }

  private void logResults(final String sessionId, final TaskResponse response) {
    final String logsDir = configService.getResultLogDir();
    if (logsDir != null && !logsDir.isEmpty()) {
      try {
        final Path dirPath = Path.of(logsDir).resolve(sessionId);
        Files.createDirectories(dirPath);
        final Path logFile = dirPath.resolve("summary.log");
        final String content = "Status: " + response.status() + "\n\nOutput:\n" + response.output();
        Files.writeString(logFile, content);
        if (log.isInfoEnabled()) {
          log.info("Logged quality check results for session {}", sessionId);
        }
      } catch (IOException e) {
        if (log.isErrorEnabled()) {
          log.error("Failed to log results", e);
        }
      }
    }
  }

  @SuppressWarnings("PMD.DoNotUseThreads")
  private TaskResponse tryExecuteChecks(final List<String> command, final File projectDir) {
    TaskResponse response;
    try {
      final ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.directory(projectDir);
      processBuilder.redirectErrorStream(true);
      final Process process = processBuilder.start();

      final String output = readProcessOutput(process);
      final Long timeout = configService.getExecutionTimeout();
      final boolean finished =
          process.waitFor(timeout != null ? timeout : 600, java.util.concurrent.TimeUnit.SECONDS);

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
      if (log.isErrorEnabled()) {
        log.error("Error executing task", e);
      }
      response = new TaskResponse(FAILURE_STATUS, "Error executing task: " + e.getMessage());
    } catch (InterruptedException e) {
      if (log.isErrorEnabled()) {
        log.error("Quality checks interrupted", e);
      }
      Thread.currentThread().interrupt();
      response = new TaskResponse(FAILURE_STATUS, "Execution interrupted: " + e.getMessage());
    }
    return response;
  }

  private String readProcessOutput(final Process process) throws IOException {
    try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
      return String.join("\n", reader.lines().toList());
    }
  }

  private static final class UncheckedIoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private UncheckedIoException(final String message, final Throwable cause) {
      super(message, cause);
    }
  }
}
