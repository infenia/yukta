// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.mapper.WorkflowMapper;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.WorkflowGraphService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link WorkflowDetailsController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.UnitTestShouldIncludeAssert",
})
class WorkflowDetailsControllerTest {

  /** The session service. */
  private SessionService sessionService;

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The client. */
  private WebTestClient client;

  /** A workflow with two nodes and an edge. */
  private static final WorkflowDefinition WITH_NODE =
      new WorkflowDefinition(
          "wf2",
          "desc",
          List.of(
              new WorkflowDefinition.Node("a", "x", null),
              new WorkflowDefinition.Node("b", "y", null)),
          List.of(new WorkflowDefinition.Edge("a", "b", "default")));

  /** The value. */
  private static final WorkflowDefinition DEF =
      new WorkflowDefinition("wf", "desc", List.of(), List.of());

  @BeforeEach
  void setUp() {
    sessionService = mock(SessionService.class);
    controlBus = mock(ControlBusGateway.class);
    final WorkflowGraphService graph = mock(WorkflowGraphService.class);
    when(graph.computeTopologicalOrder(DEF)).thenReturn(List.of());
    when(graph.computeTopologicalOrder(WITH_NODE)).thenReturn(List.of("a", "b"));
    when(graph.enrichNode(any()))
        .thenAnswer(
            call -> {
              final WorkflowDefinition.Node node = call.getArgument(0);
              return new WorkflowGraphService.EnrichedNodeMetadata(
                  node.nodeId(), node.type(), null, "d", null, List.of("default"));
            });
    client =
        WebTestClient.bindToController(
                new WorkflowDetailsController(
                    sessionService, graph, controlBus, Mappers.getMapper(WorkflowMapper.class)))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void testGraphForKnownAndUnknownWorkflow() {
    when(sessionService.getSessionWorkflow("s1", "wf")).thenReturn(Mono.just(DEF));
    when(sessionService.getSessionWorkflow("s1", "no")).thenReturn(Mono.empty());

    client
        .get()
        .uri("/api/sessions/s1/workflows/wf/graph")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.workflowId")
        .isEqualTo("wf");
    client.get().uri("/api/sessions/s1/workflows/no/graph").exchange().expectStatus().isNotFound();
  }

  @Test
  void testExecutionsArePagedAndMissingWorkflowIs404() {
    final var now = LocalDateTime.now();
    when(sessionService.getSessionWorkflow("s1", "wf")).thenReturn(Mono.just(DEF));
    when(sessionService.getSessionWorkflow("s1", "no")).thenReturn(Mono.empty());
    when(controlBus.getHistory("s1", "wf"))
        .thenReturn(
            Mono.just(
                List.of(
                    new WorkflowExecutionSummary("e2", "wf", "SUCCESS", now, null),
                    new WorkflowExecutionSummary("e1", "wf", "SUCCESS", now, null))));

    client
        .get()
        .uri("/api/sessions/s1/workflows/wf/executions?limit=1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Next-Cursor", "e2")
        .expectBody()
        .jsonPath("$.data[0].executionId")
        .isEqualTo("e2");
    client
        .get()
        .uri("/api/sessions/s1/workflows/no/executions")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void testGraphEnrichesNodesAndMapsEdges() {
    when(sessionService.getSessionWorkflow("s1", "wf2")).thenReturn(Mono.just(WITH_NODE));

    client
        .get()
        .uri("/api/sessions/s1/workflows/wf2/graph")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.nodes.length()")
        .isEqualTo(2)
        .jsonPath("$.data.nodes[1].nodeId")
        .isEqualTo("b")
        .jsonPath("$.data.edges[0].source")
        .isEqualTo("a")
        .jsonPath("$.data.topologicalOrder[1]")
        .isEqualTo("b");
  }
}
