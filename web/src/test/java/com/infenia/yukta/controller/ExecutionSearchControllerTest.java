// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.AvoidDuplicateLiterals"
})
class ExecutionSearchControllerTest {

  private static final LocalDateTime NOON = LocalDateTime.of(2026, 1, 2, 12, 0);

  private final SessionService sessions = mock(SessionService.class);
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);
  private final WebTestClient client =
      WebTestClient.bindToController(new ExecutionSearchController(sessions, controlBus)).build();

  private static WorkflowExecutionSummary run(
      final String runId, final String workflow, final String status, final LocalDateTime start) {
    return new WorkflowExecutionSummary(runId, workflow, status, start, null);
  }

  private void history() {
    when(sessions.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(controlBus.getHistory("s1"))
        .thenReturn(
            Mono.just(
                List.of(
                    run("a1", "wf", "SUCCESS", NOON),
                    run("a2", "wf", "FAILURE", NOON.minusDays(1)))));
    when(controlBus.getHistory("s2"))
        .thenReturn(
            Mono.just(
                List.of(
                    run("b1", "other", "SUCCESS", NOON.plusHours(1)),
                    run("b2", "wf", "QUEUED", null))));
  }

  @Test
  void runsOfAllSessions_areMergedNewestFirst_withUnstartedLast() {
    history();

    client
        .get()
        .uri("/api/executions")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(4)
        .jsonPath("$.data[0].executionId")
        .isEqualTo("b1")
        .jsonPath("$.data[0].sessionId")
        .isEqualTo("s2")
        .jsonPath("$.data[3].executionId")
        .isEqualTo("b2");
  }

  @Test
  void sessionFiltersAndLimit_narrowTheRuns() {
    history();

    client
        .get()
        .uri("/api/executions?sessionId=s1&status=failure")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1)
        .jsonPath("$.data[0].executionId")
        .isEqualTo("a2");
    client
        .get()
        .uri("/api/executions?limit=2")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(2);
    client
        .get()
        .uri("/api/executions?limit=0")
        .exchange()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1);
  }
}
