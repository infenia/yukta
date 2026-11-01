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
import com.infenia.yukta.mcp.dto.SchedulerLimitsResult;
import com.infenia.yukta.mcp.dto.SessionCreationGuide;
import com.infenia.yukta.mcp.dto.SessionCreationResult;
import com.infenia.yukta.mcp.dto.SessionDetails;
import com.infenia.yukta.mcp.dto.SessionList;
import com.infenia.yukta.mcp.dto.SessionSummary;
import com.infenia.yukta.mcp.dto.WhoamiResult;
import com.infenia.yukta.mcp.dto.WorkflowControlAction;
import com.infenia.yukta.mcp.dto.WorkflowHistory;
import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.mcp.provider.LogProvider;
import com.infenia.yukta.mcp.provider.PluginInfoProvider;
import com.infenia.yukta.mcp.provider.ResultProvider;
import com.infenia.yukta.mcp.provider.SessionInfoProvider;
import com.infenia.yukta.mcp.provider.SystemHealthProvider;
import com.infenia.yukta.mcp.provider.WorkflowControlProvider;
import com.infenia.yukta.mcp.provider.WorkflowExecutionProvider;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AppMcpToolsTest {

  private AppMcpTools mcpTools;
  private SessionInfoProvider sessionInfoProvider;
  private LogProvider logProvider;
  private WorkflowExecutionProvider workflowExecutionProvider;
  private WorkflowControlProvider workflowControlProvider;
  private PluginInfoProvider pluginInfoProvider;
  private SystemHealthProvider systemHealthProvider;
  private ResultProvider resultProvider;
  private com.infenia.yukta.mcp.provider.WorkflowVersionProvider workflowVersionProvider;
  private com.infenia.yukta.mcp.provider.SupervisorProvider supervisorProvider;

  private com.infenia.yukta.mcp.provider.MetricsProvider metricsProvider;

  @BeforeEach
  void setUp() {
    sessionInfoProvider = mock(SessionInfoProvider.class);
    logProvider = mock(LogProvider.class);
    workflowExecutionProvider = mock(WorkflowExecutionProvider.class);
    workflowControlProvider = mock(WorkflowControlProvider.class);
    pluginInfoProvider = mock(PluginInfoProvider.class);
    systemHealthProvider = mock(SystemHealthProvider.class);
    resultProvider = mock(ResultProvider.class);
    workflowVersionProvider = mock(com.infenia.yukta.mcp.provider.WorkflowVersionProvider.class);
    metricsProvider = mock(com.infenia.yukta.mcp.provider.MetricsProvider.class);
    supervisorProvider = mock(com.infenia.yukta.mcp.provider.SupervisorProvider.class);
    mcpTools =
        new AppMcpTools(
            sessionInfoProvider,
            logProvider,
            workflowExecutionProvider,
            workflowControlProvider,
            pluginInfoProvider,
            systemHealthProvider,
            resultProvider,
            workflowVersionProvider,
            metricsProvider,
            supervisorProvider);
  }

  @Test
  void testGetExecutionResultDelegatesToTheProvider() {
    var results =
        new com.infenia.yukta.mcp.dto.ExecutionResultView("e1", false, null, false, 0, null, null);
    when(resultProvider.getExecutionResult("s1", "e1", 3L, null)).thenReturn(Mono.just(results));

    StepVerifier.create(mcpTools.getExecutionResult("s1", "e1", 3L, null))
        .expectNext(results)
        .verifyComplete();
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
    when(logProvider.getExecutionLogs("s1", "e1", null, null, null)).thenReturn(Mono.just(logs));

    StepVerifier.create(mcpTools.getExecutionLogs("s1", "e1", null, null, null))
        .expectNext(logs)
        .verifyComplete();
  }

  @Test
  void testGetNodeOutput() {
    var result =
        new com.infenia.yukta.mcp.dto.NodeOutputResult(
            "e1",
            new com.infenia.yukta.logging.api.NodeOutputSlice(
                "n1", "all", 0, 0, 0, false, null, List.of()),
            "notice");
    when(logProvider.getNodeOutput("s1", "e1", "n1", "stderr", 5, null, null, null, "x", 2))
        .thenReturn(Mono.just(result));

    StepVerifier.create(
            mcpTools.getNodeOutput("s1", "e1", "n1", "stderr", 5, null, null, null, "x", 2))
        .expectNext(result)
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
    var result = new WorkflowStartResult("exec-1", false);
    when(workflowExecutionProvider.startWorkflow("s1", "w1", "k1")).thenReturn(Mono.just(result));

    StepVerifier.create(mcpTools.startWorkflow("s1", "w1", "k1"))
        .expectNext(result)
        .verifyComplete();
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
            "s1", WorkflowControlAction.PAUSE, "e1", null, null, null, true, null))
        .thenReturn(Mono.just(result));
    when(workflowControlProvider.controlWorkflow(
            "s1", WorkflowControlAction.PAUSE, "e2", null, null, null, false, null))
        .thenReturn(Mono.just(result));

    StepVerifier.create(
            mcpTools.controlWorkflow(
                "s1", WorkflowControlAction.PAUSE, "e1", null, null, null, true, null))
        .expectNext(result)
        .verifyComplete();
    StepVerifier.create(
            mcpTools.controlWorkflow(
                "s1", WorkflowControlAction.PAUSE, "e2", null, null, null, null, null))
        .expectNext(result)
        .verifyComplete();
  }

  @Test
  void testGetExecutionTree() {
    final var run =
        new com.infenia.yukta.model.execution.WorkflowExecutionSummary(
            "e1", "wf", TaskStatus.RUNNING, null, null);
    when(workflowControlProvider.getExecutionTree("s1", "e1")).thenReturn(Mono.just(List.of(run)));

    StepVerifier.create(mcpTools.getExecutionTree("s1", "e1"))
        .assertNext(
            history ->
                org.assertj.core.api.Assertions.assertThat(history.executions())
                    .containsExactly(run))
        .verifyComplete();
  }

  @Test
  void testControlNode() {
    var result = new ControlActionResult("STEP", "e1", "n1", List.of(), "ok");
    when(workflowControlProvider.controlNode("s1", "e1", "n1", NodeControlAction.STEP, null, null))
        .thenReturn(Mono.just(result));

    StepVerifier.create(mcpTools.controlNode("s1", "e1", "n1", NodeControlAction.STEP, null, null))
        .expectNext(result)
        .verifyComplete();
  }

  @Test
  void testSetNodeBreakpoint() {
    var result = new ControlActionResult("BREAKPOINT_SET", "e1", "n1", List.of(), "ok");
    when(workflowControlProvider.addBreakpoint("s1", "e1", "n1", "payload.status", "error"))
        .thenReturn(Mono.just(result));

    StepVerifier.create(mcpTools.addNodeBreakpoint("s1", "e1", "n1", "payload.status", "error"))
        .expectNext(result)
        .verifyComplete();
  }

  @Test
  void testSignalNode() {
    var result = new ControlActionResult("SIGNAL", "e1", "n1", List.of(), "ok");
    when(workflowControlProvider.signalNode("s1", "e1", "n1", "approval", "approve", "fine"))
        .thenReturn(Mono.just(result));

    StepVerifier.create(mcpTools.signalNode("s1", "e1", "n1", "approval", "approve", "fine"))
        .expectNext(result)
        .verifyComplete();
  }

  @Test
  void testListPendingApprovals() {
    var result = new com.infenia.yukta.mcp.dto.PendingApprovals("s1", List.of());
    when(workflowControlProvider.pendingApprovals("s1")).thenReturn(Mono.just(result));

    StepVerifier.create(mcpTools.listPendingApprovals("s1")).expectNext(result).verifyComplete();
  }

  @Test
  void testWorkflowVersionToolsDelegateToTheProvider() {
    var versions = new com.infenia.yukta.mcp.dto.WorkflowVersions("s1", "w1", List.of());
    var diff =
        new com.infenia.yukta.service.workflow.store.WorkflowDiff(
            false, List.of("b"), List.of(), List.of(), List.of(), List.of());
    var rollback = new com.infenia.yukta.mcp.dto.WorkflowRollback("w1", "0001-a");
    when(workflowVersionProvider.versions("s1", "w1")).thenReturn(Mono.just(versions));
    when(workflowVersionProvider.diff("s1", "w1", "0001-a", null)).thenReturn(Mono.just(diff));
    when(workflowVersionProvider.rollback("s1", "w1", "0001-a")).thenReturn(Mono.just(rollback));

    StepVerifier.create(mcpTools.listWorkflowVersions("s1", "w1"))
        .expectNext(versions)
        .verifyComplete();
    StepVerifier.create(mcpTools.diffWorkflowVersions("s1", "w1", "0001-a", null))
        .expectNext(diff)
        .verifyComplete();
    StepVerifier.create(mcpTools.rollbackWorkflow("s1", "w1", "0001-a"))
        .expectNext(rollback)
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
    var config = new SessionConfigData("s1", "n", "d", "i", null, null, null);
    when(sessionInfoProvider.createSession(config)).thenReturn(Mono.just(response));

    StepVerifier.create(mcpTools.createSession(config)).expectNext(response).verifyComplete();
  }

  @Test
  void testGetPluginCreationGuide() {
    var guide = mock(PluginCreationGuide.class);
    when(pluginInfoProvider.getPluginCreationGuide("all")).thenReturn(guide);

    StepVerifier.create(mcpTools.getPluginCreationGuide("all")).expectNext(guide).verifyComplete();
  }

  @Test
  void testNoteWorkflowVersionDelegatesToTheProvider() {
    var note = new com.infenia.yukta.mcp.dto.WorkflowVersionNote("w1", "0001-a", "why");
    when(workflowVersionProvider.note("s1", "w1", "0001-a", "why")).thenReturn(Mono.just(note));

    StepVerifier.create(mcpTools.noteWorkflowVersion("s1", "w1", "0001-a", "why"))
        .expectNext(note)
        .verifyComplete();
  }

  @Test
  void deleteTools_passForceThroughAndDefaultItToFalse() {
    final ControlActionResult done =
        new ControlActionResult("DELETE_SESSION", null, null, List.of(), "ok");
    when(sessionInfoProvider.deleteSession("s", false)).thenReturn(Mono.just(done));
    when(sessionInfoProvider.deleteWorkflow("s", "w", true)).thenReturn(Mono.just(done));

    StepVerifier.create(mcpTools.deleteSession("s", null)).expectNext(done).verifyComplete();
    StepVerifier.create(mcpTools.deleteWorkflow("s", "w", true)).expectNext(done).verifyComplete();
  }

  @Test
  void whoami_delegatesToTheHealthProvider() {
    final WhoamiResult who =
        new WhoamiResult(
            true, "alice", "OPERATOR", null, true, java.util.Map.of("startRuns", true));
    when(systemHealthProvider.whoami()).thenReturn(Mono.just(who));

    StepVerifier.create(mcpTools.whoami()).expectNext(who).verifyComplete();
  }

  @Test
  void schedulerLimitsTools_delegateToTheControlProvider() {
    final SchedulerLimitsResult limits = new SchedulerLimitsResult(4, 2, 1, 0, 100);
    when(workflowControlProvider.getSchedulerLimits()).thenReturn(Mono.just(limits));
    when(workflowControlProvider.setSchedulerLimits(4, 2)).thenReturn(Mono.just(limits));

    StepVerifier.create(mcpTools.getSchedulerLimits()).expectNext(limits).verifyComplete();
    StepVerifier.create(mcpTools.setSchedulerLimits(4, 2)).expectNext(limits).verifyComplete();
  }

  @Test
  void metricsSnapshotDelegatesToTheMetricsProvider() {
    final var snapshot =
        new com.infenia.yukta.mcp.dto.MetricsSnapshot("session:s1", 0, java.util.Map.of("a", 1.0));
    when(metricsProvider.getMetricsSnapshot("session:s1")).thenReturn(Mono.just(snapshot));

    StepVerifier.create(mcpTools.getMetricsSnapshot("session:s1"))
        .expectNext(snapshot)
        .verifyComplete();
  }

  @Test
  void supervisorToolsDelegateToTheSupervisorProvider() {
    final var listing =
        new com.infenia.yukta.mcp.dto.SupervisorListing(List.of(), List.of(), List.of("t"));
    final var binding =
        new com.infenia.yukta.mcp.dto.SupervisorBindingInfo("b", "p", "global", true);
    final var detached = new com.infenia.yukta.mcp.dto.SupervisorDetached("b", true);
    when(supervisorProvider.list()).thenReturn(Mono.just(listing));
    when(supervisorProvider.attach("p", "global", null, null)).thenReturn(Mono.just(binding));
    when(supervisorProvider.detach("b")).thenReturn(Mono.just(detached));

    StepVerifier.create(mcpTools.listSupervisors()).expectNext(listing).verifyComplete();
    StepVerifier.create(mcpTools.attachSupervisor("p", "global", null, null))
        .expectNext(binding)
        .verifyComplete();
    StepVerifier.create(mcpTools.detachSupervisor("b")).expectNext(detached).verifyComplete();
  }
}
