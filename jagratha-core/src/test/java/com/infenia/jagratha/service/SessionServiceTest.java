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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.WorkflowDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

  @TempDir Path tempDir;

  @Mock private AppConfigService configService;
  private final ObjectMapper objectMapper = new ObjectMapper();
  @Mock private WorkflowOrchestrator orchestrator;

  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    sessionService = new SessionService(configService, objectMapper, orchestrator);
  }

  @Test
  void testApplyConfigOverrides() {
    String sessionId = "sess-1";
    WorkflowDefinition workflow = new WorkflowDefinition(List.of(), List.of());
    AppConfigData data = new AppConfigData(sessionId, "/path", workflow);

    when(configService.setProjectPath(anyString(), anyString())).thenReturn(Mono.empty());
    when(configService.setWorkflow(anyString(), any())).thenReturn(Mono.empty());
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.empty());
    when(configService.getResultLogDir(anyString())).thenReturn(Mono.just("build/results"));
    when(configService.getAllConfigs(anyString())).thenReturn(Mono.just(java.util.Map.of()));

    StepVerifier.create(sessionService.applyConfigOverrides(data)).verifyComplete();
  }

  @Test
  void testSaveConfigToDiskNoDir() {
    String sessionId = "sess-nodir";
    when(configService.getResultLogDir(sessionId)).thenReturn(Mono.just(""));
    StepVerifier.create(sessionService.saveConfigToDisk(sessionId)).verifyComplete();
  }

  @Test
  void testApplyConfigOverridesPartial() {
    String sessionId = "sess-partial";
    AppConfigData data = new AppConfigData(sessionId, null, null);

    when(configService.setProjectPath(anyString(), any())).thenReturn(Mono.empty());
    when(configService.setWorkflow(anyString(), any())).thenReturn(Mono.empty());
    when(orchestrator.prepareWorkflow(any())).thenReturn(Mono.empty());
    when(configService.getResultLogDir(anyString())).thenReturn(Mono.just(tempDir.toString()));
    when(configService.getAllConfigs(anyString())).thenReturn(Mono.just(Map.of()));

    StepVerifier.create(sessionService.applyConfigOverrides(data)).verifyComplete();
  }

  @Test
  void testGetActiveSessions() {
    when(configService.getActiveSessionIds())
        .thenReturn(reactor.core.publisher.Flux.just("sess-1"));
    StepVerifier.create(sessionService.getActiveSessions()).expectNext("sess-1").verifyComplete();
  }

  @Test
  void testGetSessionConfigActive() {
    String sessionId = "sess-1";
    when(configService.isActive(sessionId)).thenReturn(Mono.just(true));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(Map.of("k", "v")));

    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(m -> "v".equals(m.get("k")))
        .verifyComplete();
  }

  @Test
  void testGetHistorySessions() throws IOException {
    Path resultsDir = tempDir.resolve("results");
    Files.createDirectories(resultsDir);
    Files.createDirectories(resultsDir.resolve("sess-hist"));

    when(configService.getResultLogDir(null)).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getFileLogDir(null)).thenReturn(Mono.just("empty"));
    when(configService.getActiveSessionIds())
        .thenReturn(reactor.core.publisher.Flux.just("sess-active"));

    StepVerifier.create(sessionService.getHistorySessions())
        .expectNext("sess-hist")
        .verifyComplete();
  }

  @Test
  void testGetAllSessionsOnDiskEmpty() {
    when(configService.getResultLogDir(null)).thenReturn(Mono.just(""));
    when(configService.getFileLogDir(null)).thenReturn(Mono.just(""));
    StepVerifier.create(sessionService.getAllSessionsOnDisk()).verifyComplete();
  }

  @Test
  void testGetSessionWorkflowFromDisk() throws IOException {
    String sessionId = "sess-disk";
    Path resultsDir = tempDir.resolve("results");
    Path sessDir = resultsDir.resolve(sessionId);
    Files.createDirectories(sessDir);

    WorkflowDefinition workflow = new WorkflowDefinition(List.of(), List.of());
    Map<String, Object> configMap = Map.of("workflow", workflow);
    Files.writeString(sessDir.resolve("config.json"), objectMapper.writeValueAsString(configMap));

    when(configService.isActive(sessionId)).thenReturn(Mono.just(false));
    when(configService.getResultLogDir(sessionId)).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(Map.of()));

    StepVerifier.create(sessionService.getSessionWorkflow(sessionId))
        .expectNext(workflow)
        .verifyComplete();
  }

  @Test
  void testGetSessionWorkflowFromDiskInvalidJson() throws IOException {
    String sessionId = "sess-invalid";
    Path resultsDir = tempDir.resolve("results");
    Path sessDir = resultsDir.resolve(sessionId);
    Files.createDirectories(sessDir);
    Files.writeString(sessDir.resolve("config.json"), "{invalid-json}");

    when(configService.isActive(sessionId)).thenReturn(Mono.just(false));
    when(configService.getResultLogDir(sessionId)).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(Map.of()));
    when(configService.getWorkflow(sessionId)).thenReturn(Mono.empty());

    StepVerifier.create(sessionService.getSessionWorkflow(sessionId)).verifyComplete();
  }

  @Test
  void testGetSessionWorkflowFromDiskNoWorkflow() throws IOException {
    String sessionId = "sess-noworkflow";
    Path resultsDir = tempDir.resolve("results");
    Path sessDir = resultsDir.resolve(sessionId);
    Files.createDirectories(sessDir);
    Files.writeString(sessDir.resolve("config.json"), "{}");

    when(configService.isActive(sessionId)).thenReturn(Mono.just(false));
    when(configService.getResultLogDir(sessionId)).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(Map.of()));
    when(configService.getWorkflow(sessionId)).thenReturn(Mono.empty());

    StepVerifier.create(sessionService.getSessionWorkflow(sessionId)).verifyComplete();
  }

  @Test
  void testGetSessionConfigFromDisk() throws IOException {
    String sessionId = "sess-disk-config";
    Path resultsDir = tempDir.resolve("results");
    Path sessDir = resultsDir.resolve(sessionId);
    Files.createDirectories(sessDir);

    Map<String, Object> configMap = Map.of("k", "v");
    Files.writeString(sessDir.resolve("config.json"), objectMapper.writeValueAsString(configMap));

    when(configService.isActive(sessionId)).thenReturn(Mono.just(false));
    when(configService.getResultLogDir(sessionId)).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.empty());

    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(m -> "v".equals(m.get("k")))
        .verifyComplete();
  }

  @Test
  void testGetSessionConfigFromDiskInvalid() throws IOException {
    String sessionId = "sess-disk-invalid";
    Path resultsDir = tempDir.resolve("results");
    Path sessDir = resultsDir.resolve(sessionId);
    Files.createDirectories(sessDir);
    Files.writeString(sessDir.resolve("config.json"), "{invalid}");

    when(configService.isActive(sessionId)).thenReturn(Mono.just(false));
    when(configService.getResultLogDir(sessionId)).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(Map.of("default", "val")));

    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(m -> "val".equals(m.get("default")))
        .verifyComplete();
  }
}
