/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.jagratha.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.plugin.AiPlugin;
import com.infenia.jagratha.plugin.JagrathaPlugin;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkflowServiceTest {

  private WorkflowService service;
  private AppConfigService configService;
  private JagrathaPlugin mockPlugin;
  private OutputProcessorPlugin mockProcessor;
  private AiPlugin mockAiPlugin;
  private TaskTrackerService mockTaskTracker;
  private FileLogService mockFileLogService;
  private SessionService mockSessionService;

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
    mockPlugin = mock(JagrathaPlugin.class);
    mockProcessor = mock(OutputProcessorPlugin.class);
    mockAiPlugin = mock(AiPlugin.class);
    mockTaskTracker = mock(TaskTrackerService.class);
    mockFileLogService = mock(FileLogService.class);
    mockSessionService = mock(SessionService.class);

    when(mockPlugin.getName()).thenReturn("gradle");
    when(mockProcessor.getName()).thenReturn("test-processor");
    when(mockAiPlugin.getName()).thenReturn("test-ai");

    service =
        new WorkflowService(
            configService,
            List.of(mockPlugin),
            List.of(mockProcessor),
            List.of(mockAiPlugin),
            mockTaskTracker,
            mockFileLogService,
            mockSessionService);

    when(configService.getFileLogDir(any())).thenReturn(Mono.just(filesDir.toString()));
    when(configService.getResultLogDir(any())).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getProjectPath(any())).thenReturn(Mono.just(projectDir.toString()));
    when(configService.getPluginName(any())).thenReturn(Mono.just("gradle"));
    when(configService.getPluginConfig(any()))
        .thenReturn(Mono.just(Map.of("gradlePath", "./gradlew")));
    when(configService.getExecutionTimeout(any())).thenReturn(Mono.just(600L));
    when(configService.getTasks(any())).thenReturn(Flux.just("test"));
    when(configService.getWorkflows(anyString())).thenReturn(Flux.empty());

    // Default lock behavior
    when(mockFileLogService.withLock(anyString(), any()))
        .thenAnswer(invocation -> invocation.getArgument(1));
  }

  @Test
  void testRunQualityChecksNoChanges() throws IOException {
    String sessionId = "session-no-changes";
    when(mockFileLogService.readLogFileSync(any())).thenReturn(Map.of());

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
    when(configService.getProjectPath(anyString())).thenReturn(Mono.just(""));

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

    when(mockPlugin.identifyModule(anyString(), anyString())).thenReturn(":module1");
    when(mockPlugin.buildTaskCommand(anyString(), anyString(), any())).thenReturn(List.of("ls"));

    when(mockFileLogService.readLogFileSync(any()))
        .thenReturn(
            new java.util.LinkedHashMap<>(
                Map.of("module1/src/Main.java", "PENDING", "root.java", "PENDING")));

    StepVerifier.create(service.runQualityChecks(sessionId))
        .assertNext(
            response -> {
              assertEquals("SUCCESS", response.status());
              assertTrue(response.output().contains("--- Module: :module1 ---"));
            })
        .verifyComplete();
  }

  @Test
  void testRunWorkflow() throws IOException {
    String sessionId = "session-workflow";
    when(mockPlugin.identifyModule(anyString(), anyString())).thenReturn("");
    when(mockPlugin.buildTaskCommand(anyString(), anyString(), any())).thenReturn(List.of("ls"));
    when(mockFileLogService.readLogFileSync(any()))
        .thenReturn(new java.util.LinkedHashMap<>(Map.of("root.java", "PENDING")));

    WorkflowConfig workflow =
        new WorkflowConfig(
            "test",
            new WorkflowConfig.ProcessorStepConfig("test-processor", Map.of()),
            new WorkflowConfig.AiStepConfig("test-ai", Map.of()));

    when(configService.getWorkflows(anyString())).thenReturn(Flux.just(workflow));
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
