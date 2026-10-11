// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.session.SessionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link SessionConfigController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.TooManyStaticImports",
  "PMD.ShortVariable"
})
class SessionConfigControllerTest {

  /** The session service. */
  private SessionService sessionService;

  /** The client. */
  private WebTestClient client;

  private static WorkflowDefinition workflow(final String id) {
    return new WorkflowDefinition(id, "desc", List.of(), List.of());
  }

  private static SessionConfigResponse config(final String id) {
    return new SessionConfigResponse(
        id, "n", "d", "i", Map.of("t", "v"), "/p", Map.of("wf", workflow("wf")));
  }

  @BeforeEach
  void setUp() {
    sessionService = mock(SessionService.class);
    client =
        WebTestClient.bindToController(
                new SessionConfigController(sessionService, Mappers.getMapper(SessionMapper.class)))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void testSessionDetailsAndNotFound() {
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config("s1")));
    when(sessionService.getSessionConfig("nope")).thenReturn(Mono.empty());

    client
        .get()
        .uri("/api/sessions/s1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.sessionId")
        .isEqualTo("s1");
    client.get().uri("/api/sessions/nope").exchange().expectStatus().isNotFound();
  }

  @Test
  void testListSessionsIsBareSortedAndPaged() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("c", "a", "b"));

    client
        .get()
        .uri("/api/sessions?limit=2")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Next-Cursor", "b")
        .expectBody()
        .jsonPath("$.data[0]")
        .isEqualTo("a")
        .jsonPath("$.data.length()")
        .isEqualTo(2);
    client
        .get()
        .uri("/api/sessions?limit=2&cursor=b")
        .exchange()
        .expectBody()
        .jsonPath("$.data[0]")
        .isEqualTo("c");
  }

  @Test
  void testSummariesLoadOnlyTheRequestedPage() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("a", "b", "c"));
    when(sessionService.getSessionConfig("a")).thenReturn(Mono.just(config("a")));
    when(sessionService.getSessionConfig("b")).thenReturn(Mono.just(config("b")));

    client
        .get()
        .uri("/api/sessions/summaries?limit=2")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Next-Cursor", "b")
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(2)
        .jsonPath("$.data[1].workflowCount")
        .isEqualTo(1);
    verify(sessionService, never()).getSessionConfig("c");
    verify(sessionService, never()).getAllSessionConfigs();
  }

  @Test
  void testSummariesSkipSessionsThatFailToLoad() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("a", "bad"));
    when(sessionService.getSessionConfig("a")).thenReturn(Mono.just(config("a")));
    when(sessionService.getSessionConfig("bad")).thenReturn(Mono.error(new RuntimeException("io")));

    client
        .get()
        .uri("/api/sessions/summaries")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.length()")
        .isEqualTo(1);
  }

  @Test
  void testGetWorkflowFoundAndMissing() {
    when(sessionService.getSessionWorkflow("s1", "wf")).thenReturn(Mono.just(workflow("wf")));
    when(sessionService.getSessionWorkflow("s1", "no")).thenReturn(Mono.empty());

    client.get().uri("/api/sessions/s1/workflows/wf").exchange().expectStatus().isOk();
    client.get().uri("/api/sessions/s1/workflows/no").exchange().expectStatus().isNotFound();
  }

  @Test
  void testWorkflowSummariesIncludeLatestStatus() {
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config("s1")));
    when(sessionService.getLatestExecutionStatusByWorkflow("s1"))
        .thenReturn(
            Mono.just(
                Map.of(
                    "wf",
                    new WorkflowExecutionSummary(
                        "e1", "wf", "SUCCESS", LocalDateTime.now(), null))));
    when(sessionService.getSessionConfig("nope")).thenReturn(Mono.empty());

    client
        .get()
        .uri("/api/sessions/s1/workflows")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].workflowId")
        .isEqualTo("wf")
        .jsonPath("$.data[0].status")
        .isEqualTo("SUCCESS");
    client.get().uri("/api/sessions/nope/workflows").exchange().expectStatus().isNotFound();
  }

  @Test
  void testApplyConfigAcceptsValidBodyAndRejectsInvalid() {
    when(sessionService.applyConfig(any())).thenReturn(Mono.empty());
    final String valid =
        "{\"sessionId\":\"s1\",\"name\":\"n\",\"description\":\"d\",\"projectPath\":\"/p\","
            + "\"initiator\":\"i\","
            + "\"workflows\":{\"wf\":{\"workflowId\":\"wf\",\"description\":\"d\","
            + "\"nodes\":[{\"nodeId\":\"a\",\"type\":\"x\",\"config\":{}}],\"edges\":[]}}}";

    client
        .post()
        .uri("/api/sessions")
        .header("Content-Type", "application/json")
        .bodyValue(valid)
        .exchange()
        .expectStatus()
        .isOk();
    client
        .post()
        .uri("/api/sessions")
        .header("Content-Type", "application/json")
        .bodyValue("{\"sessionId\":\"s1\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void testWorkflowWithoutExecutionsHasNoStatus() {
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config("s1")));
    when(sessionService.getLatestExecutionStatusByWorkflow("s1")).thenReturn(Mono.just(Map.of()));

    client
        .get()
        .uri("/api/sessions/s1/workflows")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].status")
        .doesNotExist();
  }

  @Test
  void testLastSummariesPageHasNoNextCursor() {
    when(sessionService.getSessionIds()).thenReturn(Flux.just("a"));
    when(sessionService.getSessionConfig("a")).thenReturn(Mono.just(config("a")));

    client
        .get()
        .uri("/api/sessions/summaries")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .doesNotExist("X-Next-Cursor");
  }

  @Test
  void testSessionConfigKeepsTagValuesAndWorkflows() {
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config("s1")));
    when(sessionService.getSessionConfig("nope")).thenReturn(Mono.empty());

    client
        .get()
        .uri("/api/sessions/s1/config")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.tags.t")
        .isEqualTo("v")
        .jsonPath("$.data.workflows.wf.workflowId")
        .isEqualTo("wf");
    client.get().uri("/api/sessions/nope/config").exchange().expectStatus().isNotFound();
  }
}
