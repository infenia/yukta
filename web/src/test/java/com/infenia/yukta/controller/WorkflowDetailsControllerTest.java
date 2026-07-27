// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mapper.WorkflowMapper;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowGraphService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Unit tests for {@link WorkflowDetailsController}. */
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LinguisticNaming",
  "PMD.UnitTestShouldIncludeAssert"
})
@NoArgsConstructor
class WorkflowDetailsControllerTest {

  /** Test session ID. */
  private static final String SESSION_ID = "session-a92";

  /** Test workflow ID. */
  private static final String WORKFLOW_ID = "wf-982-xk-11";

  /** Trigger node ID. */
  private static final String TRIGGER_NODE = "data-ingress";

  /** Processor node ID. */
  private static final String PROCESSOR_NODE = "vectorize-batch";

  /** Terminal node ID. */
  private static final String TERMINAL_NODE = "sink-storage";

  /** Trigger plugin type. */
  private static final String TRIGGER_TYPE = "ingress-v1";

  /** Processor plugin type. */
  private static final String PROCESSOR_TYPE = "vector-engine-v2";

  /** Terminal plugin type. */
  private static final String TERMINAL_TYPE = "storage-sink-v1";

  /** Workflow description constant. */
  private static final String DESCRIPTION = "Supply chain optimizer";

  /** Graph endpoint path. */
  private static final String GRAPH_URI =
      "/api/sessions/" + SESSION_ID + "/workflows/" + WORKFLOW_ID + "/graph";

  /** Executions endpoint path. */
  private static final String EXECUTIONS_URI =
      "/api/sessions/" + SESSION_ID + "/workflows/" + WORKFLOW_ID + "/executions";

  /** JSON path to the response data. */
  private static final String DATA_NODES = "$.data.nodes";

  /** Mock session service. */
  private SessionService sessionService;

  /** Mock workflow graph service. */
  private WorkflowGraphService workflowGraphService;

  /** Mock control bus gateway. */
  private ControlBusGateway controlBus;

  /** Web test client bound to the controller. */
  private WebTestClient webTestClient;

  @BeforeEach
  void setUp() {
    sessionService = mock(SessionService.class);
    workflowGraphService = mock(WorkflowGraphService.class);
    controlBus = mock(ControlBusGateway.class);
    final WorkflowDetailsController controller =
        new WorkflowDetailsController(
            sessionService,
            workflowGraphService,
            controlBus,
            Mappers.getMapper(WorkflowMapper.class));
    webTestClient = WebTestClient.bindToController(controller).build();
  }

  private static WorkflowDefinition diamondDefinition() {
    return new WorkflowDefinition(
        WORKFLOW_ID,
        DESCRIPTION,
        List.of(
            new WorkflowDefinition.Node(TRIGGER_NODE, TRIGGER_TYPE, Map.of()),
            new WorkflowDefinition.Node(PROCESSOR_NODE, PROCESSOR_TYPE, Map.of("batch", 45)),
            new WorkflowDefinition.Node(TERMINAL_NODE, TERMINAL_TYPE, Map.of())),
        List.of(
            new WorkflowDefinition.Edge(TRIGGER_NODE, PROCESSOR_NODE, null),
            new WorkflowDefinition.Edge(PROCESSOR_NODE, TERMINAL_NODE, "default")));
  }

  @Test
  void getWorkflowGraph_enrichesNodesWithPluginMetadata() {
    final UiDesign uiDesign = new UiDesign("<div>{{nodeId}}</div>", 200, 80);
    final WorkflowDefinition definition = diamondDefinition();
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.just(definition));

    when(workflowGraphService.enrichNode(
            argThat(n -> n != null && TRIGGER_NODE.equals(n.nodeId()))))
        .thenReturn(
            new WorkflowGraphService.EnrichedNodeMetadata(
                TRIGGER_NODE,
                TRIGGER_TYPE,
                PluginCategory.TRIGGER,
                "Ingest",
                uiDesign,
                List.of("default")));
    when(workflowGraphService.enrichNode(
            argThat(n -> n != null && PROCESSOR_NODE.equals(n.nodeId()))))
        .thenReturn(
            new WorkflowGraphService.EnrichedNodeMetadata(
                PROCESSOR_NODE,
                PROCESSOR_TYPE,
                PluginCategory.PROCESSOR,
                "Vectorize",
                null,
                List.of("default", "error")));
    when(workflowGraphService.enrichNode(
            argThat(n -> n != null && TERMINAL_NODE.equals(n.nodeId()))))
        .thenReturn(
            new WorkflowGraphService.EnrichedNodeMetadata(
                TERMINAL_NODE, TERMINAL_TYPE, PluginCategory.TERMINAL, "Store", null, List.of()));

    when(workflowGraphService.computeTopologicalOrder(definition))
        .thenReturn(List.of(TRIGGER_NODE, PROCESSOR_NODE, TERMINAL_NODE));

    webTestClient
        .get()
        .uri(GRAPH_URI)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.workflowId")
        .isEqualTo(WORKFLOW_ID)
        .jsonPath("$.data.description")
        .isEqualTo(DESCRIPTION)
        .jsonPath(DATA_NODES + "[0].nodeId")
        .isEqualTo(TRIGGER_NODE)
        .jsonPath(DATA_NODES + "[0].category")
        .isEqualTo("TRIGGER")
        .jsonPath(DATA_NODES + "[0].uiDesign.html")
        .isEqualTo("<div>{{nodeId}}</div>")
        .jsonPath(DATA_NODES + "[1].outputPorts[1]")
        .isEqualTo("error")
        .jsonPath("$.data.edges[1].sourcePort")
        .isEqualTo("default")
        .jsonPath("$.data.topologicalOrder[0]")
        .isEqualTo(TRIGGER_NODE)
        .jsonPath("$.data.topologicalOrder[2]")
        .isEqualTo(TERMINAL_NODE);
  }

  @Test
  void getWorkflowGraph_unknownPluginType_returnsBareNode() {
    final WorkflowDefinition definition =
        new WorkflowDefinition(
            WORKFLOW_ID,
            DESCRIPTION,
            List.of(new WorkflowDefinition.Node(TRIGGER_NODE, "unknown-type", Map.of())),
            List.of());
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.just(definition));

    when(workflowGraphService.enrichNode(argThat(n -> TRIGGER_NODE.equals(n.nodeId()))))
        .thenReturn(
            new WorkflowGraphService.EnrichedNodeMetadata(
                TRIGGER_NODE, "unknown-type", null, null, null, List.of()));
    when(workflowGraphService.computeTopologicalOrder(definition))
        .thenReturn(List.of(TRIGGER_NODE));

    webTestClient
        .get()
        .uri(GRAPH_URI)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DATA_NODES + "[0].nodeId")
        .isEqualTo(TRIGGER_NODE)
        .jsonPath(DATA_NODES + "[0].category")
        .doesNotExist()
        .jsonPath(DATA_NODES + "[0].uiDesign")
        .doesNotExist()
        .jsonPath("$.data.topologicalOrder[0]")
        .isEqualTo(TRIGGER_NODE);
  }

  @Test
  void getWorkflowGraph_cyclicGraph_fallsBackToDeclarationOrder() {
    final WorkflowDefinition definition =
        new WorkflowDefinition(
            WORKFLOW_ID,
            DESCRIPTION,
            List.of(
                new WorkflowDefinition.Node(TRIGGER_NODE, PROCESSOR_TYPE, Map.of()),
                new WorkflowDefinition.Node(PROCESSOR_NODE, PROCESSOR_TYPE, Map.of())),
            List.of(
                new WorkflowDefinition.Edge(TRIGGER_NODE, PROCESSOR_NODE, null),
                new WorkflowDefinition.Edge(PROCESSOR_NODE, TRIGGER_NODE, null)));
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.just(definition));

    when(workflowGraphService.enrichNode(
            argThat(n -> n != null && TRIGGER_NODE.equals(n.nodeId()))))
        .thenReturn(
            new WorkflowGraphService.EnrichedNodeMetadata(
                TRIGGER_NODE,
                PROCESSOR_TYPE,
                PluginCategory.PROCESSOR,
                "Proc",
                null,
                List.of("default")));
    when(workflowGraphService.enrichNode(
            argThat(n -> n != null && PROCESSOR_NODE.equals(n.nodeId()))))
        .thenReturn(
            new WorkflowGraphService.EnrichedNodeMetadata(
                PROCESSOR_NODE,
                PROCESSOR_TYPE,
                PluginCategory.PROCESSOR,
                "Proc",
                null,
                List.of("default")));
    when(workflowGraphService.computeTopologicalOrder(definition))
        .thenReturn(List.of(TRIGGER_NODE, PROCESSOR_NODE));

    webTestClient
        .get()
        .uri(GRAPH_URI)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.topologicalOrder[0]")
        .isEqualTo(TRIGGER_NODE)
        .jsonPath("$.data.topologicalOrder[1]")
        .isEqualTo(PROCESSOR_NODE);
  }

  @Test
  void getWorkflowGraph_edgeReferencingUnknownNode_isSkippedInTopologicalOrder() {
    final WorkflowDefinition definition =
        new WorkflowDefinition(
            WORKFLOW_ID,
            DESCRIPTION,
            List.of(new WorkflowDefinition.Node(TRIGGER_NODE, TRIGGER_TYPE, Map.of())),
            List.of(
                new WorkflowDefinition.Edge(TRIGGER_NODE, "ghost-node", null),
                new WorkflowDefinition.Edge("ghost-source", TRIGGER_NODE, null)));
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.just(definition));

    when(workflowGraphService.enrichNode(argThat(n -> TRIGGER_NODE.equals(n.nodeId()))))
        .thenReturn(
            new WorkflowGraphService.EnrichedNodeMetadata(
                TRIGGER_NODE,
                TRIGGER_TYPE,
                PluginCategory.TRIGGER,
                "Trig",
                null,
                List.of("default")));
    when(workflowGraphService.computeTopologicalOrder(definition))
        .thenReturn(List.of(TRIGGER_NODE));

    webTestClient
        .get()
        .uri(GRAPH_URI)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.topologicalOrder[0]")
        .isEqualTo(TRIGGER_NODE);
  }

  @Test
  void getWorkflowGraph_workflowNotFound_returns404() {
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID)).thenReturn(Mono.empty());

    final var result =
        webTestClient
            .get()
            .uri(GRAPH_URI)
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult(String.class);
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void getWorkflowExecutions_delegatesToWorkflowScopedHistory() {
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.just(diamondDefinition()));
    final LocalDateTime start = LocalDateTime.of(2026, 7, 26, 14, 22, 1);
    when(controlBus.getHistory(SESSION_ID, WORKFLOW_ID))
        .thenReturn(
            Mono.just(
                List.of(
                    new WorkflowExecutionSummary("exec-2", WORKFLOW_ID, "RUNNING", start, null),
                    new WorkflowExecutionSummary("exec-1", WORKFLOW_ID, "SUCCESS", start, start))));

    webTestClient
        .get()
        .uri(EXECUTIONS_URI)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executions.length()")
        .isEqualTo(2)
        .jsonPath("$.data.executions[0].executionId")
        .isEqualTo("exec-2")
        .jsonPath("$.data.executions[1].executionId")
        .isEqualTo("exec-1");
  }

  @Test
  void getWorkflowExecutions_neverRunWorkflow_returnsEmptyList() {
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.just(diamondDefinition()));
    when(controlBus.getHistory(SESSION_ID, WORKFLOW_ID)).thenReturn(Mono.just(List.of()));

    webTestClient
        .get()
        .uri(EXECUTIONS_URI)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.executions.length()")
        .isEqualTo(0);
  }

  @Test
  void getWorkflowGraph_serviceError_propagatesAsServerError() {
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.error(new IllegalStateException("store unavailable")));

    webTestClient.get().uri(GRAPH_URI).exchange().expectStatus().is5xxServerError();
  }

  @Test
  void getWorkflowExecutions_serviceError_propagatesAsServerError() {
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID))
        .thenReturn(Mono.error(new IllegalStateException("store unavailable")));

    webTestClient.get().uri(EXECUTIONS_URI).exchange().expectStatus().is5xxServerError();
  }

  @Test
  void getWorkflowExecutions_workflowNotFound_returns404() {
    when(sessionService.getSessionWorkflow(SESSION_ID, WORKFLOW_ID)).thenReturn(Mono.empty());

    final var result =
        webTestClient
            .get()
            .uri(EXECUTIONS_URI)
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult(String.class);
    assertThat(result.getStatus().value()).isEqualTo(404);
  }
}
