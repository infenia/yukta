// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.ActiveRunsException;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import com.infenia.yukta.testing.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link SessionConfigController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.TooManyStaticImports"
})
class SessionConfigControllerTest {

  /** The apply endpoint. */
  private static final String SESSIONS_URI = "/api/sessions";

  /** The content type header. */
  private static final String CONTENT_TYPE = "Content-Type";

  /** The JSON media type. */
  private static final String JSON = "application/json";

  /** A session id nothing is stored under. */
  private static final String NOPE = "nope";

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The session service. */
  private SessionService sessionService;

  /** The client. */
  private WebTestClient client;

  private static WorkflowDefinition workflow(final String id) {
    return Fixtures.workflow(id, "desc", List.of(), List.of());
  }

  private static com.infenia.yukta.model.session.SessionSummary summary(final String id) {
    return new com.infenia.yukta.model.session.SessionSummary(id, id, "", "", Map.of(), "", 1);
  }

  private static SessionConfigResponse config(final String id) {
    return new SessionConfigResponse(
        id, "n", "d", "i", Map.of("t", "v"), "/p", Map.of("wf", workflow("wf")));
  }

  @BeforeEach
  void setUp() {
    sessionService = mock(SessionService.class);
    controlBus = mock(ControlBusGateway.class);
    when(controlBus.getHistory(anyString())).thenReturn(Mono.just(List.of()));
    client =
        WebTestClient.bindToController(
                new SessionConfigController(
                    sessionService,
                    Mappers.getMapper(SessionMapper.class),
                    new ConfigRedactor(mock(PluginRegistry.class)),
                    controlBus))
            .controllerAdvice(new GlobalExceptionHandler())
            .configureClient()
            // the default 5 s is too short for the first request on a loaded CI runner
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  @Test
  void literalSecretsAreMaskedInTheConfigAndTheWorkflow() {
    final WorkflowDefinition withSecrets =
        Fixtures.workflow(
            "wf",
            "desc",
            List.of(
                Fixtures.node(
                    "n1",
                    "HTTP_REQUEST",
                    Map.of(
                        "url",
                        "http://x",
                        "headers",
                        Map.of("Authorization", "Bearer abc", "X-Id", "1")))),
            List.of());
    when(sessionService.getSessionConfig("s1"))
        .thenReturn(
            Mono.just(
                new SessionConfigResponse(
                    "s1", "n", "d", "i", Map.of(), "/p", Map.of("wf", withSecrets))));
    when(sessionService.getSessionWorkflow("s1", "wf")).thenReturn(Mono.just(withSecrets));

    client
        .get()
        .uri("/api/sessions/s1/config")
        .exchange()
        .expectBody()
        .jsonPath("$.data.workflows.wf.nodes[0].config.headers.Authorization")
        .isEqualTo("******")
        .jsonPath("$.data.workflows.wf.nodes[0].config.headers['X-Id']")
        .isEqualTo("1")
        .jsonPath("$.data.workflows.wf.nodes[0].config.url")
        .isEqualTo("http://x");
    client
        .get()
        .uri("/api/sessions/s1/workflows/wf")
        .exchange()
        .expectBody()
        .jsonPath("$.data.nodes[0].config.headers.Authorization")
        .isEqualTo("******");
  }

  @Test
  void testSessionDetailsAndNotFound() {
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config("s1")));
    when(sessionService.getSessionConfig(NOPE)).thenReturn(Mono.empty());

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
    when(sessionService.getSessionSummaries(false))
        .thenReturn(Flux.just(summary("a"), summary("b"), summary("c")));

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
  void testSummariesArePagedWithoutLoadingAnySession() {
    when(sessionService.getSessionSummaries(false))
        .thenReturn(Flux.just(summary("a"), summary("b"), summary("c")));

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
    // one read of the summaries: no session config and no definitions are loaded
    verify(sessionService, never()).getSessionConfig(anyString());
    verify(sessionService, never()).getAllSessionConfigs();
  }

  @Test
  void testSummariesCarryEachSessionsNewestRun() {
    when(sessionService.getSessionSummaries(false))
        .thenReturn(Flux.just(summary("a"), summary("b"), summary("c")));
    final Instant noon = LocalDateTime.of(2026, 10, 30, 12, 0).toInstant(ZoneOffset.UTC);
    when(controlBus.getHistory("a"))
        .thenReturn(
            Mono.just(
                List.of(
                    run("old", noon.minus(Duration.ofHours(1))),
                    run("new", noon),
                    run("queued", null))));
    when(controlBus.getHistory("b")).thenReturn(Mono.error(new IllegalStateException("down")));

    client
        .get()
        .uri("/api/sessions/summaries")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].lastRun.executionId")
        .isEqualTo("new")
        .jsonPath("$.data[0].lastRun.workflowId")
        .isEqualTo("wf")
        .jsonPath("$.data[1].sessionId")
        .isEqualTo("b")
        .jsonPath("$.data[1].lastRun")
        .doesNotExist()
        .jsonPath("$.data[2].lastRun")
        .doesNotExist();
  }

  private static WorkflowExecutionSummary run(final String id, final Instant start) {
    return Fixtures.summary(id, "wf", TaskStatus.SUCCESS, start, null);
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
                    "wf", Fixtures.summary("e1", "wf", TaskStatus.SUCCESS, Instant.now(), null))));
    when(sessionService.getSessionConfig(NOPE)).thenReturn(Mono.empty());

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

    postSession(valid).expectStatus().isOk();
    postSession("{\"sessionId\":\"s1\"}").expectStatus().isBadRequest();
  }

  private WebTestClient.ResponseSpec postSession(final String body) {
    return client.post().uri(SESSIONS_URI).header(CONTENT_TYPE, JSON).bodyValue(body).exchange();
  }

  private static String sessionBody(final String nodeExtra, final String workflowExtra) {
    return "{\"sessionId\":\"s1\",\"name\":\"n\",\"description\":\"d\",\"projectPath\":\"/p\","
        + "\"initiator\":\"i\",\"workflows\":{\"wf\":{\"workflowId\":\"wf\",\"description\":\"d\","
        + "\"nodes\":[{\"nodeId\":\"a\",\"type\":\"x\",\"config\":{}"
        + nodeExtra
        + "}],\"edges\":[]"
        + workflowExtra
        + "}}}";
  }

  @Test
  void testApplyConfigAcceptsNodeColorGroupAndGroupColors() {
    when(sessionService.applyConfig(any())).thenReturn(Mono.empty());

    postSession(
            sessionBody(",\"color\":\"teal\",\"group\":\"g\"", ",\"groupColors\":{\"g\":\"sky\"}"))
        .expectStatus()
        .isOk();

    final ArgumentCaptor<SessionConfigData> captor =
        ArgumentCaptor.forClass(SessionConfigData.class);
    verify(sessionService).applyConfig(captor.capture());
    final WorkflowDefinition stored = captor.getValue().workflows().get("wf");
    assertThat(stored.nodes().get(0).color()).isEqualTo("teal");
    assertThat(stored.nodes().get(0).group()).isEqualTo("g");
    assertThat(stored.groupColors()).containsEntry("g", "sky");
  }

  @Test
  void testApplyConfigWithoutColorFieldsKeepsThemEmpty() {
    when(sessionService.applyConfig(any())).thenReturn(Mono.empty());

    postSession(sessionBody("", "")).expectStatus().isOk();

    final ArgumentCaptor<SessionConfigData> captor =
        ArgumentCaptor.forClass(SessionConfigData.class);
    verify(sessionService).applyConfig(captor.capture());
    final WorkflowDefinition stored = captor.getValue().workflows().get("wf");
    assertThat(stored.nodes().get(0).color()).isNull();
    assertThat(stored.nodes().get(0).group()).isNull();
    assertThat(stored.groupColors()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"red", "#ff0000", "Teal", ""})
  void testApplyConfigRejectsNodeColorOutsideThePalette(final String color) {
    postSession(sessionBody(",\"color\":\"" + color + "\"", ""))
        .expectStatus()
        .isBadRequest()
        .expectBody()
        .jsonPath("$.errors[0].field")
        .value(f -> assertThat((String) f).endsWith("color"));
    verify(sessionService, never()).applyConfig(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"red", "Teal", ""})
  void testApplyConfigRejectsGroupColorOutsideThePalette(final String color) {
    postSession(sessionBody("", ",\"groupColors\":{\"g\":\"" + color + "\"}"))
        .expectStatus()
        .isBadRequest();
    verify(sessionService, never()).applyConfig(any());
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
    when(sessionService.getSessionSummaries(false)).thenReturn(Flux.just(summary("a")));

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
    when(sessionService.getSessionConfig(NOPE)).thenReturn(Mono.empty());

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

  @Test
  void testDeleteSessionAndWorkflowAnswer204AndPassForce() {
    when(sessionService.deleteSession("s1", false)).thenReturn(Mono.empty());
    when(sessionService.deleteWorkflow("s1", "wf", true)).thenReturn(Mono.empty());

    client.delete().uri("/api/sessions/s1").exchange().expectStatus().isNoContent();
    client
        .delete()
        .uri("/api/sessions/s1/workflows/wf?force=true")
        .exchange()
        .expectStatus()
        .isNoContent();
    verify(sessionService).deleteSession("s1", false);
    verify(sessionService).deleteWorkflow("s1", "wf", true);
  }

  @Test
  void testDeleteUnknownIs404AndActiveRunsAre409WithTheirIds() {
    when(sessionService.deleteSession(NOPE, false))
        .thenReturn(Mono.error(new NotFoundException("Session not found: nope")));
    when(sessionService.deleteSession("busy", false))
        .thenReturn(Mono.error(new ActiveRunsException(List.of("e1", "e2"), false)));

    client.delete().uri("/api/sessions/nope").exchange().expectStatus().isNotFound();
    client
        .delete()
        .uri("/api/sessions/busy")
        .exchange()
        .expectStatus()
        .isEqualTo(409)
        .expectBody()
        .jsonPath("$.message")
        .value(message -> assertThat((String) message).contains("e1, e2"));
  }
}
