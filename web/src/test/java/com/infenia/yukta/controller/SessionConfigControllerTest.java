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
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.NodeRequest;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.model.session.SessionConfigData;
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.TooManyStaticImports", "PMD.TooManyMethods"})
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
    final Map<String, Object> config = Map.of("workflows", Map.of(WF_ID_1, Map.of()));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));

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
            WORKFLOW_DESC,
            INITIATOR_1,
            Map.of(),
            NEW_PATH,
            Map.of(WF_ID_1, workflow));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                SESSION_ID_1, WORKFLOW_DESC, INITIATOR_1, Map.of(), NEW_PATH, Map.of()));
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
    final Map<String, Object> config = Map.of("workflows", Map.of());
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));

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
  void testGetSessionDetailsSuccess() {
    final Map<String, Object> config =
        Map.of("workflows", Map.of(WF_ID_1, Map.of(), "wf2", Map.of()));
    when(sessionService.getSessionConfig("s2")).thenReturn(Mono.just(config));

    final var result =
        webTestClient
            .get()
            .uri(API_SESSIONS + "/s2")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(DOLLAR_MESSAGE)
            .isEqualTo(SESSION_DETAILS_RETRIEVED)
            .jsonPath(DOLLAR_STATUS)
            .isEqualTo(200)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetWorkflowSuccess() {
    final WorkflowDefinition def =
        new WorkflowDefinition("wf2", "another desc", List.of(), List.of());
    when(sessionService.getSessionWorkflow("s2", "wf2")).thenReturn(Mono.just(def));

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
            SESSION_ID_1, "", INITIATOR_1, Map.of(), NEW_PATH, Map.of(WF_ID_1, workflow));

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
            SESSION_ID_1, WORKFLOW_DESC, "", Map.of(), NEW_PATH, Map.of("w1", workflow));

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
            "multi-desc",
            "initiator-1",
            Map.of(),
            "/multi/path",
            Map.of("w1", workflow1, "w2", workflow2));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                "session-multi", "multi-desc", "initiator-1", Map.of(), "/multi/path", Map.of()));
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
            SESSION_ID_1, WORKFLOW_DESC, INITIATOR_1, null, NEW_PATH, Map.of(WF_ID_1, workflow));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                SESSION_ID_1, WORKFLOW_DESC, INITIATOR_1, Map.of(), NEW_PATH, Map.of()));
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
            WORKFLOW_DESC,
            INITIATOR_1,
            Map.of(),
            NEW_PATH,
            Map.of(WF_ID_1, workflow));

    lenient()
        .when(sessionMapper.configRequestToSessionConfigData(any()))
        .thenReturn(
            new SessionConfigData(
                "error-session", WORKFLOW_DESC, INITIATOR_1, Map.of(), NEW_PATH, Map.of()));
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
}
