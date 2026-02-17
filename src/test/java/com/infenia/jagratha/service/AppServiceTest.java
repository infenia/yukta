package com.infenia.jagratha.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.plugin.AiPlugin;
import com.infenia.jagratha.plugin.GradlePlugin;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

class AppServiceTest {

  private AppService service;
  private AppConfigService configService;
  private OutputProcessorPlugin mockProcessor;
  private AiPlugin mockAiPlugin;

  @TempDir Path tempDir;
  private Path filesDir;
  private Path resultsDir;
  private Path projectDir;

  @BeforeEach
  void setUp() throws IOException {
    filesDir = tempDir.resolve("files");
    resultsDir = tempDir.resolve("results");
    projectDir = tempDir.resolve("project");
    Files.createDirectories(filesDir);
    Files.createDirectories(resultsDir);
    Files.createDirectories(projectDir);

    configService = mock(AppConfigService.class);
    mockProcessor = mock(OutputProcessorPlugin.class);
    mockAiPlugin = mock(AiPlugin.class);

    when(mockProcessor.getName()).thenReturn("test-processor");
    when(mockAiPlugin.getName()).thenReturn("test-ai");

    service =
        new AppService(
            configService,
            new ObjectMapper(),
            List.of(new GradlePlugin()),
            List.of(mockProcessor),
            List.of(mockAiPlugin));

    when(configService.getFileLogDir()).thenReturn(filesDir.toString());
    when(configService.getResultLogDir()).thenReturn(resultsDir.toString());
    when(configService.getProjectPath()).thenReturn(projectDir.toString());
    when(configService.getPluginName()).thenReturn("gradle");
    when(configService.getPluginConfig()).thenReturn(Map.of("gradlePath", "./gradlew"));
    when(configService.getExecutionTimeout()).thenReturn(600L);
    when(configService.getTasks()).thenReturn(List.of("test"));
  }

  @Test
  void testSaveFile() throws IOException {
    String path = "src/main/java/Test.java";
    String sessionId = "session-1";

    StepVerifier.create(service.saveFile(path, sessionId)).verifyComplete();

    Path expectedFile = filesDir.resolve(sessionId).resolve(sessionId + ".log");
    assertTrue(Files.exists(expectedFile));
    String content = Files.readString(expectedFile);
    assertTrue(content.contains("\"path\":\"src/main/java/Test.java\""));
    assertTrue(content.contains("\"status\":\"PENDING\""));
  }

  @Test
  void testSaveFileResetsSuccess() throws IOException {
    String path = "src/main/java/Test.java";
    String sessionId = "session-1";
    Path sessionDir = filesDir.resolve(sessionId);
    Files.createDirectories(sessionDir);
    Path logFile = sessionDir.resolve(sessionId + ".log");
    Files.writeString(logFile, "{\"path\":\"" + path + "\",\"status\":\"SUCCESS\"}\n");

    StepVerifier.create(service.saveFile(path, sessionId)).verifyComplete();

    String content = Files.readString(logFile);
    assertTrue(content.contains("\"status\":\"PENDING\""));
  }

  @Test
  void testSaveFileAbsolute() throws IOException {
    String sessionId = "session-abs";
    Path filePath = projectDir.resolve("src/main/java/Abs.java");
    Files.createDirectories(filePath.getParent());
    Files.createFile(filePath);

    StepVerifier.create(service.saveFile(filePath.toString(), sessionId)).verifyComplete();

    Path logFile = filesDir.resolve(sessionId).resolve(sessionId + ".log");
    String content = Files.readString(logFile);
    // Should be relative
    assertTrue(content.contains("\"path\":\"src/main/java/Abs.java\""));
  }

  @Test
  void testSaveFileNoPathConfigured() {
    when(configService.getFileLogDir()).thenReturn(null);
    StepVerifier.create(service.saveFile("test.java", "session-1"))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void testRunQualityChecksNoChanges() {
    String sessionId = "session-no-changes";

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("SUCCESS", response.status());
              assertEquals("No pending changes to process.", response.output());
            })
        .verifyComplete();
  }

  @Test
  void testRunQualityChecksPathNotConfigured() {
    when(configService.getProjectPath()).thenReturn(null);

    StepVerifier.create(service.runQualityChecks("session-1"))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("not configured"));
            })
        .verifyComplete();
  }

  @Test
  void testRunQualityChecksMultiModule() throws IOException {
    String sessionId = "session-multi";
    Path module1 = projectDir.resolve("module1");
    Files.createDirectories(module1);
    Files.createFile(module1.resolve("build.gradle"));

    Path sessionDir = filesDir.resolve(sessionId);
    Files.createDirectories(sessionDir);
    Path logFile = sessionDir.resolve(sessionId + ".log");
    Files.writeString(
        logFile,
        "{\"path\":\"module1/src/Main.java\",\"status\":\"PENDING\"}\n"
            + "{\"path\":\"root.java\",\"status\":\"PENDING\"}\n",
        StandardCharsets.UTF_8);

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("--- Module: :module1 ---"));
              // Since it fails immediately, it might not even reach root if :module1 fails
              // Actually, it will try tasks for :module1, fail, and break.
            })
        .verifyComplete();

    // Check that statuses are updated
    String content = Files.readString(logFile);
    assertTrue(content.contains("FAILURE"));
  }

  @Test
  void testRunQualityChecksSkipsSuccess() throws IOException {
    String sessionId = "session-skip";
    Path sessionDir = filesDir.resolve(sessionId);
    Files.createDirectories(sessionDir);
    Path logFile = sessionDir.resolve(sessionId + ".log");
    Files.writeString(
        logFile,
        "{\"path\":\"success.java\",\"status\":\"SUCCESS\"}\n"
            + "{\"path\":\"pending.java\",\"status\":\"PENDING\"}\n",
        StandardCharsets.UTF_8);

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("--- Module: root ---"));
            })
        .verifyComplete();

    String content = Files.readString(logFile);
    assertTrue(content.contains("\"path\":\"success.java\",\"status\":\"SUCCESS\""));
    assertTrue(content.contains("\"path\":\"pending.java\",\"status\":\"FAILURE\""));
  }

  @Test
  void testListAndGetLogs() throws IOException {
    String sessionId = "session-logs";
    Path sessionResultsDir = resultsDir.resolve(sessionId);
    Files.createDirectories(sessionResultsDir);
    Files.writeString(sessionResultsDir.resolve("test.log"), "test content");

    StepVerifier.create(service.listLogs(sessionId))
        .assertNext(
            logs -> {
              assertTrue(logs.contains("test.log"));
            })
        .verifyComplete();

    StepVerifier.create(service.getLogContent(sessionId, "test.log"))
        .expectNext("test content")
        .verifyComplete();
  }

  @Test
  void testFailImmediately() throws IOException {
    String sessionId = "session-fail-fast";
    when(configService.getTasks()).thenReturn(List.of("task1", "task2"));

    Path sessionDir = filesDir.resolve(sessionId);
    Files.createDirectories(sessionDir);
    Path logFile = sessionDir.resolve(sessionId + ".log");
    Files.writeString(logFile, "{\"path\":\"root.java\",\"status\":\"PENDING\"}\n");

    // Execution will fail because gradlew doesn't exist
    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("FAILURE", response.status());
              assertTrue(response.output().contains("Task: task1"));
              assertFalse(response.output().contains("Task: task2"));
            })
        .verifyComplete();
  }

  @Test
  void testRunWorkflow() throws IOException {
    String sessionId = "session-workflow";
    Path sessionDir = filesDir.resolve(sessionId);
    Files.createDirectories(sessionDir);
    Path logFile = sessionDir.resolve(sessionId + ".log");
    Files.writeString(logFile, "{\"path\":\"root.java\",\"status\":\"PENDING\"}\n");

    WorkflowConfig workflow =
        new WorkflowConfig(
            "test",
            new WorkflowConfig.ProcessorStepConfig("test-processor", Map.of()),
            new WorkflowConfig.AiStepConfig("test-ai", Map.of()));

    when(configService.getWorkflows()).thenReturn(List.of(workflow));
    when(mockProcessor.process(any()))
        .thenReturn(new OutputProcessorPlugin.ProcessorResult("SUCCESS", "proc output", null));
    when(mockAiPlugin.execute(any(), any())).thenReturn("ai response");

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("SUCCESS", response.status());
              assertTrue(response.output().contains("Task: test"));
              assertTrue(response.output().contains("Processor: test-processor"));
              assertTrue(response.output().contains("AI (test-ai)"));
            })
        .verifyComplete();
  }
}
