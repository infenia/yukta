// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.NodeRequest;
import com.infenia.yukta.dto.response.SessionDetails;
import com.infenia.yukta.dto.response.SessionListItem;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.session.SessionService;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for SessionConfigController. */
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.TooManyStaticImports",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals"
})
@NoArgsConstructor
class SessionConfigControllerTest {

  /** API endpoint path for sessions. */
  private static final String API_SESSIONS = "/api/sessions";

  /** JSONPath expression for status field. */
  private static final String DOLLAR_STATUS = "$.status";

  /** JSONPath expression for message field. */
  private static final String DOLLAR_MESSAGE = "$.message";

  /** Success message for configuration application. */
  private static final String CONFIG_APPLIED_SUCCESSFULLY = "Configuration applied successfully";

  /** Success message for retrieving session details. */
  private static final String SESSION_DETAILS_RETRIEVED = "Session details retrieved";

  /** Success message for retrieving workflow. */
  private static final String WORKFLOW_RETRIEVED = "Workflow retrieved";

  /** Success message for retrieving sessions. */
  private static final String SESSIONS_RETRIEVED = "Sessions retrieved successfully";

  /** Workflow ID constant for testing. */
  private static final String WF_ID_1 = "wf1";

  /** Workflow ID constant for testing. */
  private static final String WF_ID_2 = "wf2";

  /** JSONPath expression for data field. */
  private static final String DOLLAR_DATA = "$.data";

  /** Workflow description constant for testing. */
  private static final String WORKFLOW_DESC = "desc";

  /** Test workflow description. */
  private static final String TEST_WORKFLOW = "Test workflow";

  /** Gradle plugin type constant. */
  private static final String GRADLE = "gradle";

  /** Session ID constant for testing. */
  private static final String SESSION_ID_1 = "session-1";

  /** Initiator constant for testing. */
  private static final String INITIATOR_1 = "initiator-1";

  /** New path constant for testing. */
  private static final String NEW_PATH = "/new/path";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webTestClient;

  /** Mock service for session operations. */
  private SessionService sessionService;

  /** Mock mapper for session data transformation. */
  private SessionMapper sessionMapper;

  @BeforeEach
  void setUp() {
    sessionService = Mockito.mock(SessionService.class);
    sessionMapper = Mockito.mock(SessionMapper.class);
    final SessionConfigController controller =
        new SessionConfigController(sessionService, sessionMapper);
    webTestClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  void testGetSessionDetails() {
    final var config =
        new SessionConfigResponse(
            "s1",
            "Test Session",
            WORKFLOW_DESC,
            "initiator",
            Map.of(),
            "/path",
            Map.of(WF_ID_1, new WorkflowDefinition("w1", "d", List.of(), List.of())));
    final var sessionDetails =
        new SessionDetails(
            "s1", "Test Session", WORKFLOW_DESC, "initiator", List.of(), "/path", List.of(WF_ID_1));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));
    lenient()
        .when(sessionMapper.sessionConfigResponseToSessionDetails(config))
        .thenReturn(sessionDetails);

    webTestClient
        .get()
        .uri(API_SESSIONS + "/s1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    when(sessionService.getSessionConfig("unknown")).thenReturn(Mono.empty());
    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/unknown")
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testGetWorkflow() {
    final WorkflowDefinition def =
        new WorkflowDefinition(WF_ID_1, WORKFLOW_DESC, List.of(), List.of());
    when(sessionService.getSessionWorkflow("s1", WF_ID_1)).thenReturn(Mono.just(def));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/s1/workflows/" + WF_ID_1)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    when(sessionService.getSessionWorkflow("s1", "unknown")).thenReturn(Mono.empty());
    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/s1/workflows/unknown")
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testApplyConfig() {
    final WorkflowDefinitionRequest workflow =
        new WorkflowDefinitionRequest(
            "w1", TEST_WORKFLOW, List.of(new NodeRequest("n1", GRADLE, Map.of())), List.of());
    final ConfigRequest request =
        new ConfigRequest(
            SESSION_ID_1,
            "Test Session",
            WORKFLOW_DESC,
            INITIATOR_1,
            Map.of(),
            NEW_PATH,
            Map.of(WF_ID_1, workflow));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                SESSION_ID_1,
                "Test Session",
                WORKFLOW_DESC,
                INITIATOR_1,
                Map.of(),
                NEW_PATH,
                Map.of()));
    when(sessionService.applyConfig(any())).thenReturn(Mono.empty());

    final var result =
        webTestClient
            .post()
            .uri(API_SESSIONS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(CONFIG_APPLIED_SUCCESSFULLY)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);

    verify(sessionMapper).configRequestToSessionConfigData(any());
    verify(sessionService).applyConfig(any());
  }

  @Test
  void testGetSessionDetailsWithNullSessionId() {
    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/")
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testGetWorkflowWithNonExistentSession() {
    when(sessionService.getSessionWorkflow("nonexistent", WF_ID_1)).thenReturn(Mono.empty());

    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/nonexistent/workflows/wf1")
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testGetSessionDetailsWithEmptyWorkflows() {
    final var config =
        new SessionConfigResponse(
            "s1", "Test Session", "desc", "initiator", Map.of(), "/path", Map.of());
    final var sessionDetails =
        new SessionDetails(
            "s1", "Test Session", "desc", "initiator", List.of(), "/path", List.of());
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));
    lenient()
        .when(sessionMapper.sessionConfigResponseToSessionDetails(config))
        .thenReturn(sessionDetails);

    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/s1")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_DATA)
            .exists()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  @SuppressWarnings("PMD.UnitTestShouldIncludeAssert")
  void testGetSessionDetailsSuccess() {
    final var wf1 = new WorkflowDefinition("wf1", "d", List.of(), List.of());
    final var wf2 = new WorkflowDefinition(WF_ID_2, "d", List.of(), List.of());
    final var config =
        new SessionConfigResponse(
            "s2",
            "Test Session",
            "desc",
            "initiator",
            Map.of(),
            "/path",
            Map.of("wf1", wf1, WF_ID_2, wf2));
    final var sessionDetails =
        new SessionDetails(
            "s2", "Test Session", "desc", "initiator", List.of(), "/path", List.of("wf1", WF_ID_2));
    when(sessionService.getSessionConfig("s2")).thenReturn(Mono.just(config));
    lenient()
        .when(sessionMapper.sessionConfigResponseToSessionDetails(config))
        .thenReturn(sessionDetails);

    webTestClient
        .get()
        .uri(API_SESSIONS + "/s2")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .jsonPath(DOLLAR_MESSAGE)
        .isEqualTo(SESSION_DETAILS_RETRIEVED)
        .jsonPath(DOLLAR_STATUS)
        .isEqualTo(200)
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));
  }

  @Test
  void testGetWorkflowSuccess() {
    final WorkflowDefinition def =
        new WorkflowDefinition(WF_ID_2, "another desc", List.of(), List.of());
    when(sessionService.getSessionWorkflow("s2", WF_ID_2)).thenReturn(Mono.just(def));

    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/s2/workflows/wf2")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(WORKFLOW_RETRIEVED)
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testApplyConfigWithEmptyDescription() {
    final WorkflowDefinitionRequest workflow =
        new WorkflowDefinitionRequest(
            "w1", TEST_WORKFLOW, List.of(new NodeRequest("n1", GRADLE, Map.of())), List.of());
    final ConfigRequest request =
        new ConfigRequest(
            SESSION_ID_1,
            "Test Session",
            "",
            INITIATOR_1,
            Map.of(),
            NEW_PATH,
            Map.of(WF_ID_1, workflow));

    final var result =
        webTestClient
            .post()
            .uri(API_SESSIONS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(400);
  }

  @Test
  void testApplyConfigWithEmptyInitiator() {
    final WorkflowDefinitionRequest workflow =
        new WorkflowDefinitionRequest(
            "w1", TEST_WORKFLOW, List.of(new NodeRequest("n1", GRADLE, Map.of())), List.of());
    final ConfigRequest request =
        new ConfigRequest(
            SESSION_ID_1,
            "Test Session",
            WORKFLOW_DESC,
            "",
            Map.of(),
            NEW_PATH,
            Map.of("w1", workflow));

    final var result =
        webTestClient
            .post()
            .uri(API_SESSIONS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(400);
  }

  @Test
  void testApplyConfigWithMultipleWorkflows() {
    final WorkflowDefinitionRequest workflow1 =
        new WorkflowDefinitionRequest(
            "w1", "First workflow", List.of(new NodeRequest("n1", GRADLE, Map.of())), List.of());
    final WorkflowDefinitionRequest workflow2 =
        new WorkflowDefinitionRequest(
            "w2", "Second workflow", List.of(new NodeRequest("n2", "maven", Map.of())), List.of());
    final ConfigRequest request =
        new ConfigRequest(
            "session-multi",
            "Test Session",
            "multi-desc",
            "initiator-1",
            Map.of(),
            "/multi/path",
            Map.of("w1", workflow1, "w2", workflow2));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                "session-multi",
                "Test Session",
                "multi-desc",
                "initiator-1",
                Map.of(),
                "/multi/path",
                Map.of()));
    when(sessionService.applyConfig(any())).thenReturn(Mono.empty());

    final var result =
        webTestClient
            .post()
            .uri(API_SESSIONS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(CONFIG_APPLIED_SUCCESSFULLY)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testApplyConfigWithNullTags() {
    final WorkflowDefinitionRequest workflow =
        new WorkflowDefinitionRequest(
            "w1", TEST_WORKFLOW, List.of(new NodeRequest("n1", GRADLE, Map.of())), List.of());
    final ConfigRequest request =
        new ConfigRequest(
            SESSION_ID_1,
            "Test Session",
            WORKFLOW_DESC,
            INITIATOR_1,
            null,
            NEW_PATH,
            Map.of(WF_ID_1, workflow));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                SESSION_ID_1,
                "Test Session",
                WORKFLOW_DESC,
                INITIATOR_1,
                Map.of(),
                NEW_PATH,
                Map.of()));
    when(sessionService.applyConfig(any())).thenReturn(Mono.empty());

    final var result =
        webTestClient
            .post()
            .uri(API_SESSIONS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetSessionDetailsWithServiceError() {
    when(sessionService.getSessionConfig("error-session"))
        .thenReturn(Mono.error(new RuntimeException("Service error")));

    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/error-session")
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  @Test
  void testGetWorkflowWithServiceError() {
    when(sessionService.getSessionWorkflow("s1", "error-wf"))
        .thenReturn(Mono.error(new RuntimeException("Service error")));

    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/s1/workflows/error-wf")
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  @Test
  void testApplyConfigWithServiceError() {
    final WorkflowDefinitionRequest workflow =
        new WorkflowDefinitionRequest(
            "w1", TEST_WORKFLOW, List.of(new NodeRequest("n1", GRADLE, Map.of())), List.of());
    final ConfigRequest request =
        new ConfigRequest(
            "error-session",
            "Test Session",
            WORKFLOW_DESC,
            INITIATOR_1,
            Map.of(),
            NEW_PATH,
            Map.of(WF_ID_1, workflow));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                "error-session",
                "Test Session",
                WORKFLOW_DESC,
                INITIATOR_1,
                Map.of(),
                NEW_PATH,
                Map.of()));
    when(sessionService.applyConfig(any()))
        .thenReturn(Mono.error(new RuntimeException("Service error")));

    final var result =
        webTestClient
            .post()
            .uri(API_SESSIONS)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectStatus()
            .is5xxServerError()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(500);
  }

  @Test
  void testListSessionsWithSessions() {
    // Arrange
    final List<String> expectedSessionIds = List.of("session-1", "session-2", "session-3");
    when(sessionService.getSessionIds()).thenReturn(Flux.fromIterable(expectedSessionIds));

    // Act & Assert
    webTestClient
        .get()
        .uri(API_SESSIONS)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_STATUS)
        .isEqualTo(200)
        .jsonPath(DOLLAR_MESSAGE)
        .isEqualTo(SESSIONS_RETRIEVED)
        .jsonPath(DOLLAR_DATA)
        .exists()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    verify(sessionService).getSessionIds();
  }

  @Test
  void testListSessionsNoSessions() {
    // Arrange
    when(sessionService.getSessionIds()).thenReturn(Flux.empty());

    // Act & Assert
    webTestClient
        .get()
        .uri(API_SESSIONS)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_STATUS)
        .isEqualTo(200)
        .jsonPath(DOLLAR_MESSAGE)
        .isEqualTo(SESSIONS_RETRIEVED)
        .jsonPath(DOLLAR_DATA)
        .exists()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    verify(sessionService).getSessionIds();
  }

  @Test
  void testListSessionsWithError() {
    // Arrange
    when(sessionService.getSessionIds())
        .thenReturn(Flux.error(new RuntimeException("Database connection failed")));

    // Act & Assert
    webTestClient
        .get()
        .uri(API_SESSIONS)
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody()
        .jsonPath(DOLLAR_STATUS)
        .isEqualTo(500)
        .jsonPath("$.errors")
        .exists()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(500));

    verify(sessionService).getSessionIds();
  }

  @Test
  void testListSessionSummariesWithSessions() {
    final SessionConfigResponse config1 =
        new SessionConfigResponse(
            "sess-1",
            "Session 1",
            "Description 1",
            "user1",
            Map.of("tag1", "val1"),
            "/path1",
            Map.of(WF_ID_1, new WorkflowDefinition(WF_ID_1, "desc", List.of(), List.of())));
    final SessionConfigResponse config2 =
        new SessionConfigResponse(
            "sess-2",
            "Session 2",
            "Description 2",
            "user2",
            Map.of("tag2", "val2"),
            "/path2",
            Map.of(
                WF_ID_1, new WorkflowDefinition(WF_ID_1, "desc", List.of(), List.of()),
                WF_ID_2, new WorkflowDefinition(WF_ID_2, "desc", List.of(), List.of())));

    when(sessionService.getAllSessionConfigs()).thenReturn(Flux.just(config1, config2));
    when(sessionMapper.sessionConfigResponseToSessionListItem(config1))
        .thenReturn(
            new SessionListItem(
                "sess-1", "Session 1", "Description 1", "user1", List.of("tag1"), "/path1", 1));
    when(sessionMapper.sessionConfigResponseToSessionListItem(config2))
        .thenReturn(
            new SessionListItem(
                "sess-2", "Session 2", "Description 2", "user2", List.of("tag2"), "/path2", 2));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/summaries")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .jsonPath("$.data.sessions")
        .isArray()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    verify(sessionService).getAllSessionConfigs();
  }

  @Test
  void testListSessionSummariesNoSessions() {
    when(sessionService.getAllSessionConfigs()).thenReturn(Flux.empty());

    webTestClient
        .get()
        .uri(API_SESSIONS + "/summaries")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .jsonPath("$.data.sessions")
        .isArray()
        .jsonPath("$.data.sessions")
        .isEmpty()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    verify(sessionService).getAllSessionConfigs();
  }

  @Test
  void testListSessionSummariesWithError() {
    when(sessionService.getAllSessionConfigs())
        .thenReturn(Flux.error(new RuntimeException("Service error")));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/summaries")
        .exchange()
        .expectStatus()
        .is5xxServerError()
        .expectBody()
        .jsonPath(DOLLAR_STATUS)
        .isEqualTo(500)
        .jsonPath("$.errors")
        .exists()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(500));

    verify(sessionService).getAllSessionConfigs();
  }

  @Test
  void testGetSessionWorkflowsSuccess() {
    when(sessionService.getSessionConfig("sess-123"))
        .thenReturn(
            Mono.just(
                new SessionConfigResponse(
                    "sess-123", "name", "desc", "user", Map.of(), "/path", Map.of())));
    when(sessionService.getLatestExecutionStatusByWorkflow("sess-123"))
        .thenReturn(Mono.just(Map.of()));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/sess-123/workflows")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .jsonPath("$.data.workflows")
        .isArray()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    verify(sessionService).getSessionConfig("sess-123");
    verify(sessionService).getLatestExecutionStatusByWorkflow("sess-123");
  }

  @Test
  void testGetSessionWorkflowsSessionNotFound() {
    when(sessionService.getSessionConfig("sess-missing")).thenReturn(Mono.empty());

    webTestClient
        .get()
        .uri(API_SESSIONS + "/sess-missing/workflows")
        .exchange()
        .expectStatus()
        .isNotFound()
        .expectBody()
        .jsonPath(DOLLAR_STATUS)
        .isEqualTo(404)
        .jsonPath(DOLLAR_MESSAGE)
        .exists()
        .jsonPath("$.error")
        .exists()
        .jsonPath("$.path")
        .exists()
        .jsonPath("$.errors")
        .isArray()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(404));

    verify(sessionService).getSessionConfig("sess-missing");
  }

  @Test
  void testGetSessionWorkflowsWithError() {
    when(sessionService.getSessionConfig("sess-123"))
        .thenReturn(
            Mono.just(
                new SessionConfigResponse(
                    "sess-123", "name", "desc", "user", Map.of(), "/path", Map.of())));
    when(sessionService.getLatestExecutionStatusByWorkflow("sess-123"))
        .thenReturn(Mono.error(new RuntimeException("Service error")));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/sess-123/workflows")
        .exchange()
        .expectStatus()
        .isEqualTo(500)
        .expectBody()
        .jsonPath(DOLLAR_STATUS)
        .isEqualTo(500)
        .jsonPath(DOLLAR_MESSAGE)
        .isEqualTo("An internal server error occurred. Please try again later.");

    verify(sessionService).getSessionConfig("sess-123");
    verify(sessionService).getLatestExecutionStatusByWorkflow("sess-123");
  }

  @Test
  void testGetSessionWorkflowsWithMultipleWorkflows() {
    final var config =
        new SessionConfigResponse(
            "sess-multi",
            "Multi Workflow",
            "desc",
            "user",
            Map.of(),
            "/path",
            Map.of(
                WF_ID_1, new WorkflowDefinition(WF_ID_1, "desc1", List.of(), List.of()),
                WF_ID_2, new WorkflowDefinition(WF_ID_2, "desc2", List.of(), List.of())));
    when(sessionService.getSessionConfig("sess-multi")).thenReturn(Mono.just(config));
    when(sessionService.getLatestExecutionStatusByWorkflow("sess-multi"))
        .thenReturn(
            Mono.just(
                Map.of(
                    WF_ID_1,
                    new WorkflowExecutionSummary("exec1", WF_ID_1, "SUCCESS", null, null),
                    WF_ID_2,
                    new WorkflowExecutionSummary("exec2", WF_ID_2, "RUNNING", null, null))));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/sess-multi/workflows")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .jsonPath("$.data.workflows")
        .isArray()
        .jsonPath("$.data.workflows.length()")
        .isEqualTo(2)
        .jsonPath("$.data.workflows[0].workflowId")
        .exists()
        .jsonPath("$.data.workflows[0].description")
        .exists()
        .jsonPath("$.data.workflows[0].nodeCount")
        .isNumber()
        .jsonPath("$.data.workflows[0].edgeCount")
        .isNumber()
        .jsonPath("$.data.workflows[0].status")
        .isNotEmpty()
        .jsonPath("$.data.workflows[1].workflowId")
        .exists()
        .jsonPath("$.data.workflows[1].description")
        .exists()
        .jsonPath("$.data.workflows[1].nodeCount")
        .isNumber()
        .jsonPath("$.data.workflows[1].edgeCount")
        .isNumber()
        .jsonPath("$.data.workflows[1].status")
        .isNotEmpty();

    verify(sessionService).getSessionConfig("sess-multi");
    verify(sessionService).getLatestExecutionStatusByWorkflow("sess-multi");
  }

  @Test
  void testGetSessionWorkflowsWithWorkflowsButNoStatus() {
    final var config =
        new SessionConfigResponse(
            "sess-unstatus",
            "Unstatus",
            "desc",
            "user",
            Map.of(),
            "/path",
            Map.of(WF_ID_1, new WorkflowDefinition(WF_ID_1, "desc1", List.of(), List.of())));
    when(sessionService.getSessionConfig("sess-unstatus")).thenReturn(Mono.just(config));
    when(sessionService.getLatestExecutionStatusByWorkflow("sess-unstatus"))
        .thenReturn(Mono.just(Map.of()));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/sess-unstatus/workflows")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath(DOLLAR_DATA)
        .exists()
        .jsonPath("$.data.workflows[0].status")
        .doesNotExist()
        .consumeWith(response -> assertThat(response.getStatus().value()).isEqualTo(200));

    verify(sessionService).getSessionConfig("sess-unstatus");
    verify(sessionService).getLatestExecutionStatusByWorkflow("sess-unstatus");
  }

  @Test
  void testGetSessionWorkflowsWithEmptyWorkflows() {
    final var config =
        new SessionConfigResponse(
            "sess-empty-wf", "Empty", "desc", "user", Map.of(), "/path", Map.of());
    when(sessionService.getSessionConfig("sess-empty-wf")).thenReturn(Mono.just(config));
    when(sessionService.getLatestExecutionStatusByWorkflow("sess-empty-wf"))
        .thenReturn(Mono.just(Map.of()));

    webTestClient
        .get()
        .uri(API_SESSIONS + "/sess-empty-wf/workflows")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.workflows")
        .isArray()
        .jsonPath("$.data.workflows.length()")
        .isEqualTo(0);

    verify(sessionService).getSessionConfig("sess-empty-wf");
    verify(sessionService).getLatestExecutionStatusByWorkflow("sess-empty-wf");
  }
}
