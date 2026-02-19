package com.infenia.jagratha.service;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.exception.UncheckedIoException;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.plugin.AiPlugin;
import com.infenia.jagratha.plugin.JagrathaPlugin;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.StringDecoder;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Service for orchestrating quality check workflows. */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.OnlyOneReturn", "PMD.CouplingBetweenObjects"})
public class WorkflowService {

  private final AppConfigService configService;
  private final List<JagrathaPlugin> plugins;
  private final List<OutputProcessorPlugin> processorPlugins;
  private final List<AiPlugin> aiPlugins;
  private final TaskTrackerService tracker;
  private final FileLogService fileLogService;
  private final SessionService sessionService;

  private static final String FAILURE_STATUS = "FAILURE";
  private static final String SUCCESS_STATUS = "SUCCESS";
  private static final int SB_CAPACITY = 2048;

  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /**
   * Run quality checks on the external project and log results.
   *
   * @param sessionId the session identifier
   * @return Mono containing the task response
   */
  public Mono<TaskResponse> runQualityChecks(final String sessionId) {
    return Mono.defer(() -> executeQualityChecks(sessionId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<TaskResponse> executeQualityChecks(final String sessionId) {
    return Mono.zip(
            configService.getProjectPath(sessionId),
            configService.getFileLogDir(sessionId),
            configService.getPluginName(sessionId))
        .flatMap(
            tuple -> {
              final String projectRoot = tuple.getT1();
              final String logsDir = tuple.getT2();
              final String pluginName = tuple.getT3();

              if (projectRoot == null || projectRoot.isEmpty()) {
                return respondAndLog(
                    sessionId, FAILURE_STATUS, "External project path not configured.");
              }
              if (logsDir == null || logsDir.isEmpty()) {
                return respondAndLog(
                    sessionId, FAILURE_STATUS, "File log directory not configured.");
              }
              if (pluginName == null || pluginName.isEmpty()) {
                return respondAndLog(
                    sessionId,
                    FAILURE_STATUS,
                    "No plugins configured. Please use the /api/config endpoint to initialize the "
                        + "project configuration.");
              }

              final File projectDir = new File(projectRoot);
              if (!projectDir.exists() || !projectDir.isDirectory()) {
                return respondAndLog(
                    sessionId, FAILURE_STATUS, "Project directory does not exist.");
              }

              return processSessionLogs(sessionId, projectRoot, projectDir, logsDir);
            })
        .flatMap(
            response ->
                logResults(sessionId, response)
                    .then(
                        Mono.fromRunnable(
                            () -> tracker.finishWorkflow(sessionId, response.status())))
                    .thenReturn(response));
  }

  private Mono<TaskResponse> respondAndLog(
      final String sessionId, final String status, final String msg) {
    final TaskResponse response = new TaskResponse(status, msg);
    return logResults(sessionId, response).thenReturn(response);
  }

  private Mono<JagrathaPlugin> getActivePlugin(final String sessionId) {
    return configService
        .getPluginName(sessionId)
        .map(
            pluginName -> {
              if (pluginName == null || pluginName.isEmpty()) {
                throw new IllegalStateException(
                    "No plugins configured. Please use the /api/config endpoint to initialize "
                        + "the project configuration.");
              }
              return plugins.stream()
                  .filter(p -> pluginName.equals(p.getName()))
                  .findFirst()
                  .orElseThrow(() -> new IllegalStateException("Plugin not found: " + pluginName));
            });
  }

  private Mono<TaskResponse> processSessionLogs(
      final String sessionId,
      final String projectRoot,
      final File projectDir,
      final String logsDir) {

    final Path logFile = Path.of(logsDir).resolve(sessionId).resolve(sessionId + ".log");

    return fileLogService.withLock(
        sessionId,
        Mono.defer(
            () ->
                Mono.fromCallable(() -> fileLogService.readLogFileSync(logFile))
                    .flatMap(
                        files ->
                            getActivePlugin(sessionId)
                                .flatMap(
                                    plugin ->
                                        identifyPendingByModule(plugin, projectRoot, files)
                                            .flatMap(
                                                pendingByModule -> {
                                                  if (pendingByModule.isEmpty()) {
                                                    return Mono.just(
                                                        new TaskResponse(
                                                            SUCCESS_STATUS,
                                                            "No pending changes to process."));
                                                  }

                                                  return executeAndLogChecks(
                                                      sessionId,
                                                      projectDir,
                                                      logFile,
                                                      files,
                                                      pendingByModule);
                                                })))
                    .onErrorResume(
                        e -> {
                          log.error("Failed to manage session logs", e);
                          return Mono.just(
                              new TaskResponse(
                                  FAILURE_STATUS, "Error managing logs: " + e.getMessage()));
                        })));
  }

  private Mono<Map<String, List<String>>> identifyPendingByModule(
      final JagrathaPlugin plugin, final String projectRoot, final Map<String, String> files) {
    return Mono.fromCallable(
        () ->
            files.entrySet().stream()
                .filter(entry -> !SUCCESS_STATUS.equals(entry.getValue()))
                .collect(
                    Collectors.groupingBy(
                        entry -> plugin.identifyModule(projectRoot, entry.getKey()),
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList()))));
  }

  private Mono<TaskResponse> executeAndLogChecks(
      final String sessionId,
      final File projectDir,
      final Path logFile,
      final Map<String, String> files,
      final Map<String, List<String>> pendingByModule) {
    return Mono.zip(
            configService.getWorkflows(sessionId).collectList(),
            configService.getTasks(sessionId).collectList())
        .flatMap(
            tuple -> {
              final List<WorkflowConfig> workflows = tuple.getT1();
              final List<String> tasks = tuple.getT2();

              final List<String> taskNames = new ArrayList<>();
              if (workflows.isEmpty()) {
                taskNames.addAll(tasks);
              } else {
                workflows.forEach(w -> taskNames.add(w.task()));
              }
              tracker.startWorkflow(sessionId, taskNames);

              return runChecksForModules(projectDir, pendingByModule, files, sessionId)
                  .flatMap(
                      response ->
                          Mono.fromRunnable(
                                  () -> {
                                    try {
                                      fileLogService.writeLogFileSync(logFile, files);
                                    } catch (IOException e) {
                                      throw new UncheckedIoException("Failed to log", e);
                                    }
                                  })
                              .subscribeOn(Schedulers.boundedElastic())
                              .thenReturn(response));
            });
  }

  private Mono<TaskResponse> runChecksForModules(
      final File projectDir,
      final Map<String, List<String>> pendingByModule,
      final Map<String, String> allFiles,
      final String sessionId) {

    final StringBuilder combinedOutput = new StringBuilder(SB_CAPACITY);

    // Using Flux to process modules sequentially
    return Flux.fromIterable(pendingByModule.entrySet())
        .concatMap(
            entry -> {
              final String module = entry.getKey();
              return executeModuleTasks(projectDir, sessionId, combinedOutput, module)
                  .doOnNext(
                      moduleRes -> {
                        for (final String file : entry.getValue()) {
                          allFiles.put(file, moduleRes.status());
                        }
                      });
            })
        .takeUntil(res -> FAILURE_STATUS.equals(res.status()))
        .collectList()
        .map(
            results -> {
              String overallStatus = SUCCESS_STATUS;
              if (results.stream().anyMatch(res -> FAILURE_STATUS.equals(res.status()))) {
                overallStatus = FAILURE_STATUS;
              }
              return new TaskResponse(overallStatus, combinedOutput.toString());
            });
  }

  private Mono<TaskResponse> executeModuleTasks(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final String module) {

    combinedOutput
        .append("--- Module: ")
        .append(module.isEmpty() ? "root" : module)
        .append(" ---\n");

    return configService
        .getWorkflows(sessionId)
        .collectList()
        .flatMap(
            workflows -> {
              if (!workflows.isEmpty()) {
                return runWorkflows(projectDir, sessionId, combinedOutput, module, workflows);
              }
              return runSimpleTasks(projectDir, sessionId, combinedOutput, module);
            });
  }

  private Mono<TaskResponse> runWorkflows(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final String module,
      final List<WorkflowConfig> workflows) {

    return Flux.fromIterable(workflows)
        .concatMap(
            workflow -> executeWorkflow(projectDir, sessionId, combinedOutput, module, workflow))
        .takeUntil(res -> FAILURE_STATUS.equals(res.status()))
        .collectList()
        .map(
            results -> {
              if (results.stream().anyMatch(res -> FAILURE_STATUS.equals(res.status()))) {
                return new TaskResponse(FAILURE_STATUS, "");
              }
              return new TaskResponse(SUCCESS_STATUS, "");
            });
  }

  private Mono<TaskResponse> runSimpleTasks(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final String module) {
    return configService
        .getTasks(sessionId)
        .collectList()
        .flatMap(
            tasks ->
                configService
                    .getPluginConfig(sessionId)
                    .flatMap(
                        pluginConfig ->
                            Flux.fromIterable(tasks)
                                .concatMap(
                                    task -> {
                                      tracker.updateTaskStatus(sessionId, task, module, "RUNNING");
                                      return executeSingleTask(
                                              projectDir, sessionId, module, task, pluginConfig)
                                          .doOnNext(
                                              res -> {
                                                tracker.updateTaskStatus(
                                                    sessionId, task, module, res.status());
                                                combinedOutput
                                                    .append("Task: ")
                                                    .append(task)
                                                    .append(" - ")
                                                    .append(res.status())
                                                    .append('\n')
                                                    .append(res.output())
                                                    .append("\n\n");
                                              });
                                    })
                                .takeUntil(res -> FAILURE_STATUS.equals(res.status()))
                                .collectList()
                                .map(
                                    results -> {
                                      if (results.stream()
                                          .anyMatch(res -> FAILURE_STATUS.equals(res.status()))) {
                                        return new TaskResponse(FAILURE_STATUS, "");
                                      }
                                      return new TaskResponse(SUCCESS_STATUS, "");
                                    })));
  }

  private Mono<TaskResponse> executeWorkflow(
      final File projectDir,
      final String sessionId,
      final StringBuilder combinedOutput,
      final String module,
      final WorkflowConfig workflow) {

    tracker.updateTaskStatus(sessionId, workflow.task(), module, "RUNNING");
    return configService
        .getPluginConfig(sessionId)
        .flatMap(
            pluginConfig ->
                executeSingleTask(projectDir, sessionId, module, workflow.task(), pluginConfig))
        .flatMap(
            taskRes -> {
              tracker.updateTaskStatus(sessionId, workflow.task(), module, taskRes.status());

              combinedOutput
                  .append("Task: ")
                  .append(workflow.task())
                  .append(" - ")
                  .append(taskRes.status())
                  .append('\n')
                  .append(taskRes.output())
                  .append('\n');

              if (FAILURE_STATUS.equals(taskRes.status()) && workflow.processor() == null) {
                return Mono.just(new TaskResponse(FAILURE_STATUS, combinedOutput.toString()));
              }

              if (workflow.processor() != null) {
                final OutputProcessorPlugin processor = findProcessor(workflow.processor().name());
                final Mono<OutputProcessorPlugin.ProcessorResult> procResMono =
                    Mono.zip(
                            configService.getProjectPath(sessionId),
                            configService.getResultLogDir(sessionId))
                        .map(
                            tuple ->
                                processor.process(
                                    new OutputProcessorPlugin.ProcessorInput(
                                        sessionId,
                                        tuple.getT1(),
                                        module,
                                        workflow.task(),
                                        taskRes.output(),
                                        tuple.getT2(),
                                        workflow.processor().config())));

                return procResMono.flatMap(
                    procRes -> {
                      combinedOutput
                          .append("Processor: ")
                          .append(workflow.processor().name())
                          .append(" - ")
                          .append(procRes.status())
                          .append('\n')
                          .append(procRes.output())
                          .append('\n');

                      if (FAILURE_STATUS.equals(procRes.status())) {
                        return Mono.just(
                            new TaskResponse(FAILURE_STATUS, combinedOutput.toString()));
                      }

                      return runAiStep(
                              sessionId,
                              module,
                              workflow,
                              taskRes,
                              procRes.artifactPath(),
                              combinedOutput)
                          .map(
                              ignored -> {
                                combinedOutput.append('\n');
                                return new TaskResponse(SUCCESS_STATUS, "");
                              });
                    });
              }

              return runAiStep(sessionId, module, workflow, taskRes, null, combinedOutput)
                  .map(
                      ignored -> {
                        combinedOutput.append('\n');
                        return new TaskResponse(SUCCESS_STATUS, "");
                      });
            });
  }

  private Mono<Void> runAiStep(
      final String sessionId,
      final String module,
      final WorkflowConfig workflow,
      final TaskResponse taskRes,
      final String artifactPath,
      final StringBuilder combinedOutput) {
    if (workflow.aiStep() != null) {
      final AiPlugin aiPlugin = findAiPlugin(workflow.aiStep().name());
      return constructPrompt(workflow.aiStep().config(), taskRes.output(), artifactPath)
          .flatMap(
              prompt ->
                  Mono.fromCallable(() -> aiPlugin.execute(prompt, workflow.aiStep().config()))
                      .subscribeOn(Schedulers.boundedElastic())
                      .flatMap(
                          aiResponse -> {
                            combinedOutput
                                .append("AI (")
                                .append(workflow.aiStep().name())
                                .append("):\n")
                                .append(aiResponse)
                                .append('\n');

                            return saveAiLog(
                                sessionId,
                                module,
                                workflow.task(),
                                workflow.aiStep().name(),
                                aiResponse);
                          }));
    }
    return Mono.empty();
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

  private Mono<String> constructPrompt(
      final Map<String, Object> config, final String taskOutput, final String artifactPath) {
    return Mono.fromCallable(
            () -> {
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
                          "{{processorOutput}}",
                          "Error reading processor artifact: " + e.getMessage());
                }
              } else {
                result = result.replace("{{processorOutput}}", "No processor output available.");
              }
              return result;
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<Void> saveAiLog(
      final String sessionId,
      final String module,
      final String task,
      final String aiName,
      final String response) {
    final String timestamp = LocalDateTime.now().format(FORMATTER);
    final String modulePart = module.isEmpty() ? "root" : module.replace(":", "-").substring(1);
    final String logFileName =
        String.format("%s-%s-%s-%s.log", modulePart, task, aiName, timestamp);
    return writeSessionLog(sessionId, logFileName, response);
  }

  private Mono<TaskResponse> executeSingleTask(
      final File projectDir,
      final String sessionId,
      final String module,
      final String task,
      final Map<String, Object> pluginConfig) {
    return getActivePlugin(sessionId)
        .flatMap(
            plugin -> {
              final List<String> command = plugin.buildTaskCommand(module, task, pluginConfig);
              final String timestamp = LocalDateTime.now().format(FORMATTER);
              final String namePrefix =
                  module.isEmpty() ? "root" : module.replace(":", "-").substring(1);
              final String logFileName = String.format("%s-%s-%s.log", namePrefix, task, timestamp);

              if (log.isInfoEnabled()) {
                log.info("Running quality check: {}", String.join(" ", command));
              }

              return tryExecuteChecks(command, projectDir, sessionId)
                  .flatMap(res -> saveTaskLog(sessionId, logFileName, res).thenReturn(res));
            });
  }

  private Mono<Void> saveTaskLog(
      final String sessionId, final String fileName, final TaskResponse res) {
    final String content = "Status: " + res.status() + "\n\nOutput:\n" + res.output();
    return writeSessionLog(sessionId, fileName, content);
  }

  private Mono<Void> logResults(final String sessionId, final TaskResponse response) {
    final String content = "Status: " + response.status() + "\n\nOutput:\n" + response.output();
    return writeSessionLog(sessionId, "summary.log", content)
        .doOnSuccess(
            v -> {
              if (log.isInfoEnabled()) {
                log.info("Logged quality check results for session {}", sessionId);
              }
            });
  }

  private Mono<Void> writeSessionLog(
      final String sessionId, final String fileName, final String content) {
    return configService
        .getResultLogDir(sessionId)
        .flatMap(
            logsDir -> {
              if (logsDir == null || logsDir.isEmpty()) {
                return Mono.empty();
              }
              return Mono.fromRunnable(
                      () -> {
                        try {
                          final Path dirPath = Path.of(logsDir).resolve(sessionId);
                          Files.createDirectories(dirPath);
                          Files.writeString(dirPath.resolve(fileName), content);
                        } catch (IOException e) {
                          log.error("Failed to log {} for session {}", fileName, sessionId, e);
                        }
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .then();
            });
  }

  private Mono<TaskResponse> tryExecuteChecks(
      final List<String> command, final File projectDir, final String sessionId) {
    return Mono.fromCallable(
            () -> {
              final ProcessBuilder processBuilder = new ProcessBuilder(command);
              processBuilder.directory(projectDir);
              processBuilder.redirectErrorStream(true);
              return processBuilder.start();
            })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(
            process -> {
              final StringBuilder output = new StringBuilder();

              // Use DataBufferUtils to read the process output stream reactively
              final Flux<String> outputFlux =
                  DataBufferUtils.readInputStream(
                          process::getInputStream, DefaultDataBufferFactory.sharedInstance, 4096)
                      .transform(
                          flux -> StringDecoder.textPlainOnly().decode(flux, null, null, Map.of()));

              final Mono<String> readOutputMono =
                  outputFlux
                      .doOnNext(
                          line -> {
                            output.append(line).append('\n');
                            tracker.appendLog(sessionId, line);
                          })
                      .then(Mono.fromSupplier(output::toString));

              final Mono<Long> timeoutMono = configService.getExecutionTimeout(sessionId);
              final Mono<Integer> exitCodeMono =
                  timeoutMono.flatMap(
                      timeoutVal ->
                          Mono.fromFuture(process.onExit())
                              .map(Process::exitValue)
                              .timeout(Duration.ofSeconds(timeoutVal != null ? timeoutVal : 600))
                              .onErrorResume(
                                  TimeoutException.class,
                                  e -> {
                                    process.destroyForcibly();
                                    return Mono.error(
                                        new TimeoutException("Timeout while running checks."));
                                  }));

              return Mono.zip(exitCodeMono, readOutputMono)
                  .map(
                      tuple ->
                          new TaskResponse(
                              tuple.getT1() == 0 ? SUCCESS_STATUS : FAILURE_STATUS, tuple.getT2()))
                  .onErrorResume(
                      TimeoutException.class,
                      e ->
                          Mono.just(
                              new TaskResponse(
                                  FAILURE_STATUS, "Timeout while running checks.\n" + output)));
            })
        .onErrorResume(
            IOException.class,
            e ->
                Mono.just(
                    new TaskResponse(FAILURE_STATUS, "Error executing task: " + e.getMessage())));
  }
}
