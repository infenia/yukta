// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.response.ControlBusStatus;
import com.infenia.yukta.dto.response.PluginCreationGuide;
import com.infenia.yukta.dto.response.PluginDetails;
import com.infenia.yukta.dto.response.PluginSummary;
import com.infenia.yukta.dto.response.SessionCreationGuide;
import com.infenia.yukta.dto.response.SessionCreationResponse;
import com.infenia.yukta.dto.response.SessionDetails;
import com.infenia.yukta.dto.response.SessionInfo;
import com.infenia.yukta.mcp.provider.DefaultLogProvider;
import com.infenia.yukta.mcp.provider.DefaultPluginInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSessionInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSystemHealthProvider;
import com.infenia.yukta.mcp.provider.DefaultWorkflowExecutionProvider;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AppMcpToolsTest {

  private AppMcpTools mcpTools;
  private DefaultSessionInfoProvider sessionInfoProvider;
  private DefaultLogProvider logProvider;
  private DefaultWorkflowExecutionProvider workflowExecutionProvider;
  private DefaultPluginInfoProvider pluginInfoProvider;
  private DefaultSystemHealthProvider systemHealthProvider;

  @BeforeEach
  void setUp() {
    sessionInfoProvider = mock(DefaultSessionInfoProvider.class);
    logProvider = mock(DefaultLogProvider.class);
    workflowExecutionProvider = mock(DefaultWorkflowExecutionProvider.class);
    pluginInfoProvider = mock(DefaultPluginInfoProvider.class);
    systemHealthProvider = mock(DefaultSystemHealthProvider.class);
    mcpTools =
        new AppMcpTools(
            sessionInfoProvider,
            logProvider,
            workflowExecutionProvider,
            pluginInfoProvider,
            systemHealthProvider);
  }

  @Test
  void testGetSessionDetails() {
    var details = mock(SessionDetails.class);
    when(sessionInfoProvider.getSessionDetails("session-1")).thenReturn(Mono.just(details));

    StepVerifier.create(mcpTools.getSessionDetails("session-1"))
        .expectNext(details)
        .verifyComplete();
  }

  @Test
  void testListSessions() {
    var info = mock(SessionInfo.class);
    when(sessionInfoProvider.listSessions()).thenReturn(Flux.just(info));

    StepVerifier.create(mcpTools.listSessions()).expectNext(info).verifyComplete();
  }

  @Test
  void testStreamSessionLogs() {
    when(logProvider.streamSessionLogs("s1", null, null, null)).thenReturn(Flux.just("log1"));

    StepVerifier.create(mcpTools.streamSessionLogs("s1", null, null, null))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void testGetWorkflowExecutionLogs() {
    when(logProvider.getWorkflowExecutionLogs("s1", "e1", null)).thenReturn(Mono.just("logs"));

    StepVerifier.create(mcpTools.getWorkflowExecutionLogs("s1", "e1", null))
        .expectNext("logs")
        .verifyComplete();
  }

  @Test
  void testGetWorkflowDetails() {
    var def = mock(WorkflowDefinition.class);
    when(workflowExecutionProvider.getWorkflowDetails("s1", "w1")).thenReturn(Mono.just(def));

    StepVerifier.create(mcpTools.getWorkflowDetails("s1", "w1")).expectNext(def).verifyComplete();
  }

  @Test
  void testTriggerWorkflow() {
    when(workflowExecutionProvider.triggerWorkflow("s1", "w1", null))
        .thenReturn(Mono.just("exec-1"));

    StepVerifier.create(mcpTools.triggerWorkflow("s1", "w1", null))
        .expectNext("exec-1")
        .verifyComplete();
  }

  @Test
  void testGetWorkflowStatus() {
    var summary = mock(WorkflowExecutionSummary.class);
    when(workflowExecutionProvider.getWorkflowStatus("s1", "e1")).thenReturn(Mono.just(summary));

    StepVerifier.create(mcpTools.getWorkflowStatus("s1", "e1"))
        .expectNext(summary)
        .verifyComplete();
  }

  @Test
  void testListPlugins() {
    var summary = mock(PluginSummary.class);
    when(pluginInfoProvider.listPlugins()).thenReturn(List.of(summary));

    StepVerifier.create(mcpTools.listPlugins()).expectNext(summary).verifyComplete();
  }

  @Test
  void testGetPluginDetails() {
    var details = mock(PluginDetails.class);
    when(pluginInfoProvider.getPluginDetails("test")).thenReturn(details);

    StepVerifier.create(mcpTools.getPluginDetails("test")).expectNext(details).verifyComplete();
  }

  @Test
  void testGetControlBusStatus() {
    var status = mock(ControlBusStatus.class);
    when(systemHealthProvider.getControlBusStatus(null)).thenReturn(status);

    StepVerifier.create(mcpTools.getControlBusStatus(null)).expectNext(status).verifyComplete();
  }

  @Test
  void testGetSessionCreationInstructions() {
    var guide = mock(SessionCreationGuide.class);
    when(sessionInfoProvider.getSessionCreationInstructions()).thenReturn(guide);

    StepVerifier.create(mcpTools.getSessionCreationInstructions())
        .expectNext(guide)
        .verifyComplete();
  }

  @Test
  void testCreateSession() {
    var response = mock(SessionCreationResponse.class);
    when(sessionInfoProvider.createSession("{}")).thenReturn(Mono.just(response));

    StepVerifier.create(mcpTools.createSession("{}")).expectNext(response).verifyComplete();
  }

  @Test
  void testGetPluginCreationGuide() {
    var guide = mock(PluginCreationGuide.class);
    when(pluginInfoProvider.getPluginCreationGuide("all")).thenReturn(guide);

    StepVerifier.create(mcpTools.getPluginCreationGuide("all")).expectNext(guide).verifyComplete();
  }
}
