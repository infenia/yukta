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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.PluginRegistration;
import com.infenia.jagratha.model.WorkflowConfig;
import com.infenia.jagratha.plugin.AiPlugin;
import com.infenia.jagratha.plugin.OutputProcessorPlugin;
import com.infenia.jagratha.plugin.gradle.GradlePlugin;
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

class SessionServiceTest {

  private SessionService service;
  private AppConfigService configService;
  private OutputProcessorPlugin mockProcessor;
  private AiPlugin mockAiPlugin;

  @TempDir Path tempDir;
  private Path resultsDir;

  @BeforeEach
  void setUp() throws IOException {
    resultsDir = tempDir.resolve("results");
    Files.createDirectories(resultsDir);

    configService = mock(AppConfigService.class);
    mockProcessor = mock(OutputProcessorPlugin.class);
    mockAiPlugin = mock(AiPlugin.class);

    when(mockProcessor.getName()).thenReturn("test-processor");
    when(mockAiPlugin.getName()).thenReturn("test-ai");

    service =
        new SessionService(
            configService,
            new ObjectMapper(),
            List.of(new GradlePlugin()),
            List.of(mockProcessor),
            List.of(mockAiPlugin));

    when(configService.getResultLogDir(any())).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getFileLogDir(any())).thenReturn(Mono.just(""));
  }

  @Test
  void testApplyConfigOverrides() {
    List<PluginRegistration> plugins = List.of(new PluginRegistration("gradle", Map.of()));
    AppConfigData data =
        new AppConfigData(
            "session-1", "/new/path", plugins, List.of(new WorkflowConfig("task1", null, null)));

    when(configService.setProjectPath(any(), any())).thenReturn(Mono.empty());
    when(configService.setPlugins(any(), any())).thenReturn(Mono.empty());
    when(configService.setWorkflows(any(), any())).thenReturn(Mono.empty());
    when(configService.getAllConfigs(any())).thenReturn(Mono.just(Map.of()));

    StepVerifier.create(service.applyConfigOverrides(data)).verifyComplete();

    verify(configService).setProjectPath("session-1", "/new/path");
    verify(configService).setPlugins("session-1", plugins);
    verify(configService).setWorkflows("session-1", data.workflows());
  }

  @Test
  @SuppressWarnings("unchecked")
  void testActiveAndHistorySessions() throws IOException {
    String activeSess = "active-sess";
    String historySess = "history-sess";

    when(configService.getActiveSessionIds()).thenReturn(Flux.just(activeSess));
    when(configService.getResultLogDir(null)).thenReturn(Mono.just(resultsDir.toString()));
    when(configService.getFileLogDir(null)).thenReturn(Mono.just(""));

    // Create history session on disk
    Files.createDirectories(resultsDir.resolve(historySess));

    StepVerifier.create(service.getActiveSessions()).expectNext(activeSess).verifyComplete();

    StepVerifier.create(service.getHistorySessions()).expectNext(historySess).verifyComplete();
  }

  @Test
  void testSaveAndLoadConfig() throws IOException {
    String sessionId = "config-sess";
    Map<String, Object> config = Map.of("projectPath", "/path/to/project");
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(config));
    when(configService.isActive(sessionId)).thenReturn(Mono.just(true));

    StepVerifier.create(service.saveConfigToDisk(sessionId)).verifyComplete();

    Path configFile = resultsDir.resolve(sessionId).resolve("config.json");
    assertTrue(Files.exists(configFile));

    // Now make it inactive
    when(configService.isActive(sessionId)).thenReturn(Mono.just(false));
    StepVerifier.create(service.getSessionConfig(sessionId))
        .assertNext(loaded -> assertEquals("/path/to/project", loaded.get("projectPath")))
        .verifyComplete();
  }

  @Test
  void testApplyConfigOverridesValidationFailure() {
    PluginRegistration invalidPlugin = new PluginRegistration("gradle", Map.of("gradlePath", 123));
    AppConfigData data = new AppConfigData("session-1", "/path", List.of(invalidPlugin), List.of());

    assertThrows(IllegalArgumentException.class, () -> service.applyConfigOverrides(data).block());
  }

  @Test
  void testApplyConfigOverridesPluginNotFound() {
    PluginRegistration unknownPlugin = new PluginRegistration("unknown", Map.of());
    AppConfigData data = new AppConfigData("session-1", "/path", List.of(unknownPlugin), List.of());

    assertThrows(IllegalArgumentException.class, () -> service.applyConfigOverrides(data).block());
  }
}
