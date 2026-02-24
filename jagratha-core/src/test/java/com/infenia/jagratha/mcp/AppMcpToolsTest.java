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
package com.infenia.jagratha.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.jagratha.model.WorkflowExecution;
import com.infenia.jagratha.plugin.PluginCategory;
import com.infenia.jagratha.plugin.WorkflowPlugin;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import com.infenia.jagratha.service.WorkflowRegistry;
import com.infenia.jagratha.service.WorkflowService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AppMcpToolsTest {

  private AppMcpTools mcpTools;
  private WorkflowService workflowService;
  private SessionService sessionService;
  private TaskTrackerService trackerService;
  private WorkflowRegistry registry;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    workflowService = mock(WorkflowService.class);
    sessionService = mock(SessionService.class);
    trackerService = mock(TaskTrackerService.class);
    registry = mock(WorkflowRegistry.class);
    objectMapper = new ObjectMapper();
    mcpTools =
        new AppMcpTools(workflowService, sessionService, trackerService, registry, objectMapper);
  }

  @Test
  void testListSessions() {
    when(sessionService.getActiveSessions()).thenReturn(Flux.just("session-1"));
    when(sessionService.getSessionConfig("session-1"))
        .thenReturn(
            Mono.just(
                Map.of(
                    "initiator", "user",
                    "initiatedTime", "2023-01-01T00:00:00Z",
                    "description", "test desc",
                    "tags", Map.of("env", "prod"))));
    when(trackerService.getHistory("session-1")).thenReturn(List.of());

    StepVerifier.create(mcpTools.listSessions())
        .expectNextMatches(
            list ->
                list.size() == 1
                    && list.get(0).sessionId().equals("session-1")
                    && list.get(0).initiator().equals("user"))
        .verifyComplete();
  }

  @Test
  void testGetSessionDetails() {
    when(sessionService.getSessionConfig("session-1"))
        .thenReturn(Mono.just(Map.of("workflows", Map.of("wf-1", Map.of()))));

    StepVerifier.create(mcpTools.getSessionDetails("session-1"))
        .expectNextMatches(
            details ->
                details.sessionId().equals("session-1") && details.workflowIds().contains("wf-1"))
        .verifyComplete();
  }

  @Test
  void testTriggerWorkflow() {
    WorkflowExecution execution = new WorkflowExecution("exec-1", Mono.empty());
    when(workflowService.runWorkflow(eq("sess-1"), eq("wf-1"), anyMap())).thenReturn(execution);

    StepVerifier.create(mcpTools.triggerWorkflow("sess-1", "wf-1", "{\"key\":\"val\"}"))
        .expectNext("exec-1")
        .verifyComplete();
  }

  @Test
  void testListPlugins() {
    WorkflowPlugin plugin = mock(WorkflowPlugin.class);
    when(plugin.getType()).thenReturn("test-plugin");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.listPlugins()).thenReturn(List.of(plugin));

    var plugins = mcpTools.listPlugins();
    assertEquals(1, plugins.size());
    assertEquals("test-plugin", plugins.get(0).type());
  }

  @Test
  void testGetPluginDetails() {
    WorkflowPlugin plugin = mock(WorkflowPlugin.class);
    when(plugin.getType()).thenReturn("test-plugin");
    when(plugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(plugin.getDescription()).thenReturn("desc");
    when(plugin.getUsagePattern()).thenReturn("pattern");
    when(registry.get("test-plugin")).thenReturn(plugin);

    var details = mcpTools.getPluginDetails("test-plugin");
    assertEquals("test-plugin", details.type());
    assertEquals("desc", details.description());
  }
}
