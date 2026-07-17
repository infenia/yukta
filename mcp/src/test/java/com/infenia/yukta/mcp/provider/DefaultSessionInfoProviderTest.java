// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.PluginReference;
import com.infenia.yukta.mcp.dto.SessionCreationGuide;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
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
  private PluginRegistry registry;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    sessionService = mock(SessionService.class);
    registry = mock(PluginRegistry.class);
    objectMapper = new ObjectMapper();
    provider = new DefaultSessionInfoProvider(sessionService, registry, objectMapper);
  }

  @Test
  void testGetSessionDetails() {
    final var wf =
        new com.infenia.yukta.model.workflow.WorkflowDefinition("wf-1", "d", List.of(), List.of());
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "session-1", "desc", "initiator", Map.of(), "/path", Map.of("wf-1", wf));
    when(sessionService.getSessionConfig("session-1")).thenReturn(Mono.just(config));

    StepVerifier.create(provider.getSessionDetails("session-1"))
        .expectNextMatches(
            details ->
                details.sessionId().equals("session-1") && details.workflowIds().contains("wf-1"))
        .verifyComplete();
  }

  @Test
  void testGetSessionDetailsNotFound() {
    when(sessionService.getSessionConfig("nope")).thenReturn(Mono.empty());

    StepVerifier.create(provider.getSessionDetails("nope"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Session not found: nope"))
        .verify();
  }

  @Test
  void testListSessionsSkipsFailingSession() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    final var wf =
        new com.infenia.yukta.model.workflow.WorkflowDefinition("wf-1", "d", List.of(), List.of());
    final var config =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s1", "desc", "initiator", Map.of(), "/path", Map.of("wf-1", wf));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));
    when(sessionService.getSessionConfig("s2"))
        .thenReturn(Mono.error(new RuntimeException("fail")));

    StepVerifier.create(provider.listSessions())
        .expectNextMatches(
            sessions ->
                sessions.size() == 1
                    && sessions.get(0).sessionId().equals("s1")
                    && sessions.get(0).workflowCount() == 1)
        .verifyComplete();
  }

  @Test
  void testListSessionsMultiple() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("s1", "s2", "s3"));
    final var wf =
        new com.infenia.yukta.model.workflow.WorkflowDefinition("wf", "d", List.of(), List.of());
    final var config1 =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s1", "desc", "initiator", Map.of(), "/path", Map.of("wf", wf));
    final var config2 =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s2", "desc", "initiator", Map.of(), "/path", Map.of());
    final var config3 =
        new com.infenia.yukta.model.session.SessionConfigResponse(
            "s3", "desc", "initiator", Map.of(), "/path", Map.of());
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config1));
    when(sessionService.getSessionConfig("s2")).thenReturn(Mono.just(config2));
    when(sessionService.getSessionConfig("s3")).thenReturn(Mono.just(config3));

    StepVerifier.create(provider.listSessions())
        .expectNextMatches(sessions -> sessions.size() == 3)
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
    Plugin plugin1 = mock(Plugin.class);
    when(plugin1.getType()).thenReturn("test-plugin");
    when(plugin1.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin1.getDescription()).thenReturn("Test plugin description");

    Plugin plugin2 = mock(Plugin.class);
    when(plugin2.getType()).thenReturn("another-plugin");
    when(plugin2.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(plugin2.getDescription()).thenReturn("Another plugin");

    when(registry.listPlugins()).thenReturn(List.of(plugin1, plugin2));

    SessionCreationGuide guide = provider.getSessionCreationInstructions();

    assertNotNull(guide);
    assertNotNull(guide.availablePlugins());
    assertFalse(guide.availablePlugins().isEmpty());

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
        .expectNextMatches(res -> res.success() && res.sessionId().equals("s1"))
        .verifyComplete();
  }

  @Test
  void testCreateSessionInvalidJson() {
    StepVerifier.create(provider.createSession("invalid"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Failed to create session"))
        .verify();
  }

  @Test
  void testCreateSessionApplyConfigError() {
    when(sessionService.applyConfig(org.mockito.ArgumentMatchers.any()))
        .thenReturn(Mono.error(new RuntimeException("Config error")));

    StepVerifier.create(provider.createSession("{\"sessionId\":\"s1\", \"workflows\":{}}"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Config error")
                    && error.getCause() instanceof RuntimeException)
        .verify();
  }
}
