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
package com.infenia.yukta.mcp.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.api.PluginReference;
import com.infenia.yukta.model.api.SessionCreationGuide;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.service.SessionService;
import com.infenia.yukta.service.WorkflowRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class DefaultSessionInfoProviderTest {

  private DefaultSessionInfoProvider provider;
  private SessionService sessionService;
  private WorkflowRegistry registry;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    sessionService = mock(SessionService.class);
    registry = mock(WorkflowRegistry.class);
    objectMapper = new ObjectMapper();
    provider = new DefaultSessionInfoProvider(sessionService, registry, objectMapper);
  }

  @Test
  void testGetSessionDetails() {
    when(sessionService.getSessionConfig("session-1"))
        .thenReturn(Mono.just(Map.of("workflows", Map.of("wf-1", Map.of()))));

    StepVerifier.create(provider.getSessionDetails("session-1"))
        .expectNextMatches(
            details ->
                details.sessionId().equals("session-1") && details.workflowIds().contains("wf-1"))
        .verifyComplete();
  }

  @Test
  void testListSessions() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(Map.of()));
    when(sessionService.getSessionConfig("s2"))
        .thenReturn(Mono.error(new RuntimeException("fail")));

    StepVerifier.create(provider.listSessions())
        .expectNextMatches(info -> info.sessionId().equals("s1"))
        .verifyComplete();
  }

  @Test
  void testGetSessionCreationInstructions() {
    SessionCreationGuide guide = provider.getSessionCreationInstructions();

    assertNotNull(guide);
    assertNotNull(guide.namingConventions());
    assertFalse(guide.namingConventions().isEmpty());
    assertNotNull(guide.configurationStructure());
    assertFalse(guide.configurationStructure().isEmpty());
    assertNotNull(guide.exampleSessionConfig());
    assertFalse(guide.exampleSessionConfig().isEmpty());
    assertTrue(guide.exampleSessionConfig().contains("workflows"));
  }

  @Test
  void testGetSessionCreationInstructionsWithPlugins() {
    // Mock plugins to cover the PluginReference creation code path
    WorkflowPlugin plugin1 = mock(WorkflowPlugin.class);
    when(plugin1.getType()).thenReturn("test-plugin");
    when(plugin1.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin1.getDescription()).thenReturn("Test plugin description");

    WorkflowPlugin plugin2 = mock(WorkflowPlugin.class);
    when(plugin2.getType()).thenReturn("another-plugin");
    when(plugin2.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(plugin2.getDescription()).thenReturn("Another plugin");

    when(registry.listPlugins()).thenReturn(List.of(plugin1, plugin2));

    SessionCreationGuide guide = provider.getSessionCreationInstructions();

    assertNotNull(guide);
    assertNotNull(guide.availablePlugins());
    assertFalse(guide.availablePlugins().isEmpty());

    // Verify PluginReference objects were created correctly
    List<PluginReference> plugins = guide.availablePlugins();
    assertTrue(
        plugins.stream()
            .anyMatch(
                p ->
                    p.type().equals("test-plugin")
                        && p.category().equals("PROCESSOR")
                        && p.description().equals("Test plugin description")));
    assertTrue(
        plugins.stream()
            .anyMatch(
                p ->
                    p.type().equals("another-plugin")
                        && p.category().equals("TRIGGER")
                        && p.description().equals("Another plugin")));
  }

  @Test
  void testCreateSession() {
    when(sessionService.applyConfig(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

    StepVerifier.create(provider.createSession("{\"sessionId\":\"s1\", \"workflows\":{}}"))
        .expectNextMatches(res -> res.success())
        .verifyComplete();
  }

  @Test
  void testCreateSessionInvalidJson() {
    StepVerifier.create(provider.createSession("invalid"))
        .expectNextMatches(res -> !res.success())
        .verifyComplete();
  }

  @Test
  void testListSessionsMultiple() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2", "s3"));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(Map.of("wf", "data")));
    when(sessionService.getSessionConfig("s2")).thenReturn(Mono.just(Map.of()));
    when(sessionService.getSessionConfig("s3")).thenReturn(Mono.just(Map.of()));

    StepVerifier.create(provider.listSessions()).expectNextCount(3).verifyComplete();
  }

  @Test
  void testCreateSessionApplyConfigError() {
    when(sessionService.applyConfig(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Mono.error(new RuntimeException("Config error")));

    StepVerifier.create(provider.createSession("{\"sessionId\":\"s1\", \"workflows\":{}}"))
        .expectNextMatches(res -> !res.success() && res.warnings().size() > 0)
        .verifyComplete();
  }
}
