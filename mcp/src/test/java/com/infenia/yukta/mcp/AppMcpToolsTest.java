// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.dto.ControlBusStatus;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.dto.NodeControlAction;
import com.infenia.yukta.mcp.dto.PluginCreationGuide;
import com.infenia.yukta.mcp.dto.PluginDetails;
import com.infenia.yukta.mcp.dto.PluginList;
import com.infenia.yukta.mcp.dto.PluginSummary;
import com.infenia.yukta.mcp.dto.SessionCreationGuide;
import com.infenia.yukta.mcp.dto.SessionCreationResult;
import com.infenia.yukta.mcp.dto.SessionDetails;
import com.infenia.yukta.mcp.dto.SessionList;
import com.infenia.yukta.mcp.dto.SessionSummary;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.mcp.dto.WorkflowHistory;
import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.mcp.provider.DefaultLogProvider;
import com.infenia.yukta.mcp.provider.DefaultPluginInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSessionInfoProvider;
import com.infenia.yukta.mcp.provider.DefaultSystemHealthProvider;
import com.infenia.yukta.mcp.provider.DefaultWorkflowControlProvider;
import com.infenia.yukta.mcp.provider.DefaultWorkflowExecutionProvider;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AppMcpToolsTest {

  private AppMcpTools mcpTools;
  private DefaultSessionInfoProvider sessionInfoProvider;
  private DefaultLogProvider logProvider;
  private DefaultWorkflowExecutionProvider workflowExecutionProvider;
  private DefaultWorkflowControlProvider workflowControlProvider;
  private DefaultPluginInfoProvider pluginInfoProvider;
  private DefaultSystemHealthProvider systemHealthProvider;

  @BeforeEach
  void setUp() {
    sessionInfoProvider = mock(DefaultSessionInfoProvider.class);
    logProvider = mock(DefaultLogProvider.class);
    workflowExecutionProvider = mock(DefaultWorkflowExecutionProvider.class);
    workflowControlProvider = mock(DefaultWorkflowControlProvider.class);
    pluginInfoProvider = mock(DefaultPluginInfoProvider.class);
    systemHealthProvider = mock(DefaultSystemHealthProvider.class);
    mcpTools =
        new AppMcpTools(
            sessionInfoProvider,
            logProvider,
            workflowExecutionProvider,
            workflowControlProvider,
            pluginInfoProvider,
            systemHealthProvider);
  }

  @Test
  void testGetSessionDetails() {
    var details = new SessionDetails("session-1", List.of("wf-1"));
    when(sessionInfoProvider.getSessionDetails("session-1")).thenReturn(Mono.just(details));

    StepVerifier.create(mcpTools.getSessionDetails("session-1"))
        .expectNext(details)
        .verifyComplete();
  }

  @Test
  void testListSessions() {
    var summaries = List.of(new SessionSummary("s1", 2));
    when(sessionInfoProvider.listSessions()).thenReturn(Mono.just(summaries));

    StepVerifier.create(mcpTools.listSessions())
        .expectNext(new SessionList(summaries))
        .verifyComplete();
  }

  @Test
  void testGetExecutionLogs() {
    var logs = new ExecutionLogs("e1", 1, 1, List.of("line1"));
    when(logProvider.getExecutionLogs("s1", "e1", null, null)).thenReturn(Mono.just(logs));

    StepVerifier.create(mcpTools.getExecutionLogs("s1", "e1", null, null))
        .expectNext(logs)
        .verifyComplete();
  }

  @Test
  void testGetWorkflowDetails() {
    var def = mock(WorkflowDefinition.class);
    when(workflowExecutionProvider.getWorkflowDetails("s1", "w1")).thenReturn(Mono.just(def));

    StepVerifier.create(mcpTools.getWorkflowDetails("s1", "w1")).expectNext(def).verifyComplete();
  }

  @Test
  void testStartWorkflow() {
    var result = new WorkflowStartResult("exec-1");
    when(workflowExecutionProvider.startWorkflow("s1", "w1")).thenReturn(Mono.just(result));

    StepVerifier.create(mcpTools.startWorkflow("s1", "w1")).expectNext(result).verifyComplete();
  }

  @Test
  void testGetWorkflowStatus() {
    var progress = mock(WorkflowProgress.class);
    when(workflowExecutionProvider.getWorkflowStatus("s1", "e1")).thenReturn(Mono.just(progress));

    StepVerifier.create(mcpTools.getWorkflowStatus("s1", "e1"))
        .expectNext(progress)
        .verifyComplete();
  }

  @Test
  void testGetWorkflowHistory() {
    var summary = mock(WorkflowExecutionSummary.class);
    when(workflowExecutionProvider.getWorkflowHistory("s1"))
        .thenReturn(Mono.just(List.of(summary)));

    StepVerifier.create(mcpTools.getWorkflowHistory("s1"))
        .expectNext(new WorkflowHistory(List.of(summary)))
        .verifyComplete();
  }

  @Test
  void testControlWorkflow() {
    var result = new ControlActionResult("PAUSE", "e1", null, List.of(), "ok");
    when(workflowControlProvider.controlWorkflow(
            "s1", WorkflowControlAction.PAUSE, "e1", null, null, null))
        .thenReturn(Mono.just(result));

    StepVerifier.create(
            mcpTools.controlWorkflow("s1", WorkflowControlAction.PAUSE, "e1", null, null, null))
        .expectNext(result)
        .verifyComplete();
  }

  @Test
  void testControlNode() {
    var result = new ControlActionResult("SKIP", "e1", "n1", List.of(), "ok");
    when(workflowControlProvider.controlNode("s1", "e1", "n1", NodeControlAction.SKIP, null, null))
        .thenReturn(Mono.just(result));

    StepVerifier.create(mcpTools.controlNode("s1", "e1", "n1", NodeControlAction.SKIP, null, null))
        .expectNext(result)
        .verifyComplete();
  }

  @Test
  void testListPlugins() {
    var summary = mock(PluginSummary.class);
    when(pluginInfoProvider.listPlugins()).thenReturn(List.of(summary));

    StepVerifier.create(mcpTools.listPlugins())
        .expectNext(new PluginList(List.of(summary)))
        .verifyComplete();
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
    when(systemHealthProvider.getControlBusStatus(null)).thenReturn(Mono.just(status));

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
    var response = new SessionCreationResult("s1", List.of("wf-1"), List.of(), true);
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
