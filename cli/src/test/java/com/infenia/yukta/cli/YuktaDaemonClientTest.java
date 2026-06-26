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
package com.infenia.yukta.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.model.api.ApiResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class YuktaDaemonClientTest {

  @Mock private WebClient mockWebClient;

  @Mock private DaemonProperties mockProps;

  private YuktaDaemonClient client;

  @BeforeEach
  void setUp() {
    client = new YuktaDaemonClient(mockWebClient, mockProps);
  }

  // applySession tests

  @Test
  void applySession_validRequest_logsSessionApplied() {
    // Given
    ConfigRequest request =
        new ConfigRequest(
            "session-123",
            "Test Session",
            "Test User",
            Map.of("env", "test"),
            "/test/project",
            Map.of(
                "workflow-1",
                new WorkflowDefinitionRequest(
                    "workflow-1", "Test Workflow", List.of(), List.of())));

    ApiResponse<Void> response = ApiResponse.success(200, "Session applied", null);
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When
    client.applySession(request);

    // Then
    verify(mockWebClient).post();
  }

  @Test
  void applySession_nullResponse_doesNotThrowError() {
    // Given
    ConfigRequest request =
        new ConfigRequest(
            "session-123",
            "Test Session",
            "Test User",
            Map.of(),
            "/test/project",
            Map.of(
                "workflow-1",
                new WorkflowDefinitionRequest(
                    "workflow-1", "Test Workflow", List.of(), List.of())));

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When-Then
    client.applySession(request);
    verify(mockWebClient).post();
  }

  // triggerWorkflow tests

  @Test
  void triggerWorkflow_validRequest_returnsExecutionId() {
    // Given
    String sessionId = "session-123";
    String workflowId = "workflow-456";

    Map<String, Object> responseData = Map.of("executionId", "exec-789");
    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Workflow triggered", responseData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When
    String result = client.triggerWorkflow(sessionId, workflowId);

    // Then
    assertThat(result).isEqualTo("exec-789");
  }

  @Test
  void triggerWorkflow_nullResponse_throwsRuntimeException() {
    // Given
    String sessionId = "session-123";
    String workflowId = "workflow-456";

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When-Then
    assertThatThrownBy(() -> client.triggerWorkflow(sessionId, workflowId))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to trigger workflow");
  }

  @Test
  void triggerWorkflow_nullDataInResponse_throwsRuntimeException() {
    // Given
    String sessionId = "session-123";
    String workflowId = "workflow-456";

    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Workflow triggered", null);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When-Then
    assertThatThrownBy(() -> client.triggerWorkflow(sessionId, workflowId))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Failed to trigger workflow");
  }

  @Test
  void triggerWorkflow_missingExecutionIdInData_returnsNull() {
    // Given
    String sessionId = "session-123";
    String workflowId = "workflow-456";

    Map<String, Object> responseData = Map.of("otherKey", "value");
    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Workflow triggered", responseData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When
    String result = client.triggerWorkflow(sessionId, workflowId);

    // Then - when executionId is not in the response data, get returns null
    assertThat(result).isNull();
  }

  // getActiveNodes tests

  @Test
  void getActiveNodes_validRequest_returnsNodesList() {
    // Given
    String workflowId = "workflow-456";
    List<String> nodesList = List.of("node-1", "node-2", "node-3");
    ApiResponse<List<String>> response = ApiResponse.success(200, "Nodes retrieved", nodesList);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    List<String> result = client.getActiveNodes(workflowId);

    // Then
    assertThat(result).containsExactly("node-1", "node-2", "node-3");
  }

  @Test
  void getActiveNodes_nullResponse_returnsEmptyList() {
    // Given
    String workflowId = "workflow-456";

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    List<String> result = client.getActiveNodes(workflowId);

    // Then
    assertThat(result).isEmpty();
  }

  // getAllActiveNodes tests

  @Test
  void getAllActiveNodes_validRequest_returnsNodesList() {
    // Given
    List<String> nodesList = List.of("node-1", "node-2");
    ApiResponse<List<String>> response = ApiResponse.success(200, "All nodes retrieved", nodesList);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    List<String> result = client.getAllActiveNodes();

    // Then
    assertThat(result).containsExactly("node-1", "node-2");
  }

  @Test
  void getAllActiveNodes_nullResponse_returnsEmptyList() {
    // Given
    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    List<String> result = client.getAllActiveNodes();

    // Then
    assertThat(result).isEmpty();
  }

  // getLastHeartbeat tests

  @Test
  void getLastHeartbeat_validRequest_returnsHeartbeatMap() {
    // Given
    String workflowId = "workflow-456";
    String nodeId = "node-1";
    Map<String, Object> heartbeatData =
        new HashMap<>(
            Map.of("timestamp", "2026-01-01T00:00:00", "status", "healthy", "uptime", 3600));
    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Heartbeat retrieved", heartbeatData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getLastHeartbeat(workflowId, nodeId);

    // Then
    assertThat(result)
        .containsEntry("timestamp", "2026-01-01T00:00:00")
        .containsEntry("status", "healthy")
        .containsEntry("uptime", 3600);
  }

  @Test
  void getLastHeartbeat_nullResponse_returnsEmptyMap() {
    // Given
    String workflowId = "workflow-456";
    String nodeId = "node-1";

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getLastHeartbeat(workflowId, nodeId);

    // Then
    assertThat(result).isEmpty();
  }

  // sendCommand tests

  @Test
  void sendCommand_validRequest_returnsResponseData() {
    // Given
    String workflowId = "workflow-456";
    String nodeId = "node-1";
    Map<String, Object> payload = Map.of("action", "restart");
    Map<String, Object> responseData = Map.of("result", "success");
    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Command sent", responseData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.sendCommand(workflowId, nodeId, payload);

    // Then
    assertThat(result).containsEntry("result", "success");
  }

  @Test
  void sendCommand_nullResponse_returnsEmptyMap() {
    // Given
    String workflowId = "workflow-456";
    String nodeId = "node-1";
    Map<String, Object> payload = Map.of("action", "restart");

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestBodyUriSpec uriSpec =
        mock(WebClient.RequestBodyUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.bodyValue(any())).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.post()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.sendCommand(workflowId, nodeId, payload);

    // Then
    assertThat(result).isEmpty();
  }

  // getProgress tests

  @Test
  void getProgress_validRequest_returnsProgressMap() {
    // Given
    String executionId = "exec-789";
    Map<String, Object> progressData =
        new HashMap<>(Map.of("percentage", 50, "status", "in-progress"));
    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Progress retrieved", progressData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getProgress(executionId);

    // Then
    assertThat(result).containsEntry("percentage", 50).containsEntry("status", "in-progress");
  }

  @Test
  void getProgress_nullResponse_returnsEmptyMap() {
    // Given
    String executionId = "exec-789";

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getProgress(executionId);

    // Then
    assertThat(result).isEmpty();
  }

  // getHistory tests

  @Test
  void getHistory_validRequest_returnsHistoryList() {
    // Given
    String sessionId = "session-123";
    List<Map<String, Object>> historyData =
        List.of(
            Map.of("id", "exec-1", "status", "completed"),
            Map.of("id", "exec-2", "status", "in-progress"));
    ApiResponse<List<Map<String, Object>>> response =
        ApiResponse.success(200, "History retrieved", historyData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    List<Map<String, Object>> result = client.getHistory(sessionId);

    // Then
    assertThat(result)
        .hasSize(2)
        .containsExactly(
            Map.of("id", "exec-1", "status", "completed"),
            Map.of("id", "exec-2", "status", "in-progress"));
  }

  @Test
  void getHistory_nullResponse_returnsEmptyList() {
    // Given
    String sessionId = "session-123";

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    List<Map<String, Object>> result = client.getHistory(sessionId);

    // Then
    assertThat(result).isEmpty();
  }

  // streamProgress tests

  @Test
  void streamProgress_withData_callsLineConsumerForEachLine() {
    // Given
    String executionId = "exec-789";
    Consumer<String> lineConsumer = mock(Consumer.class);
    Flux<String> flux = Flux.just("line1", "line2", "line3");

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToFlux(String.class)).thenReturn(flux);

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    client.streamProgress(executionId, lineConsumer);

    // Then
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(lineConsumer, org.mockito.Mockito.times(3)).accept(captor.capture());
    assertThat(captor.getAllValues()).containsExactly("line1", "line2", "line3");
  }

  @Test
  void streamProgress_emptyStream_completesWithoutCallingConsumer() {
    // Given
    String executionId = "exec-789";
    Consumer<String> lineConsumer = mock(Consumer.class);
    Flux<String> flux = Flux.empty();

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToFlux(String.class)).thenReturn(flux);

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    client.streamProgress(executionId, lineConsumer);

    // Then
    verify(lineConsumer, never()).accept(anyString());
  }

  // streamLogs tests

  @Test
  void streamLogs_withData_callsLineConsumerForEachLine() {
    // Given
    String executionId = "exec-789";
    Consumer<String> lineConsumer = mock(Consumer.class);
    Flux<String> flux = Flux.just("log1", "log2");

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToFlux(String.class)).thenReturn(flux);

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    client.streamLogs(executionId, lineConsumer);

    // Then
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(lineConsumer, org.mockito.Mockito.times(2)).accept(captor.capture());
    assertThat(captor.getAllValues()).containsExactly("log1", "log2");
  }

  @Test
  void streamLogs_emptyStream_completesWithoutCallingConsumer() {
    // Given
    String executionId = "exec-789";
    Consumer<String> lineConsumer = mock(Consumer.class);
    Flux<String> flux = Flux.empty();

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToFlux(String.class)).thenReturn(flux);

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString())).thenAnswer(inv -> uriSpec);
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    client.streamLogs(executionId, lineConsumer);

    // Then
    verify(lineConsumer, never()).accept(anyString());
  }

  // getSessionDetails tests

  @Test
  void getSessionDetails_validRequest_returnsSessionData() {
    // Given
    String sessionId = "session-123";
    Map<String, Object> sessionData =
        Map.of("sessionId", "session-123", "workflowIds", List.of("workflow-1", "workflow-2"));
    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Session details retrieved", sessionData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getSessionDetails(sessionId);

    // Then
    assertThat(result).isEqualTo(sessionData);
  }

  @Test
  void getSessionDetails_nullResponse_returnsEmptyMap() {
    // Given
    String sessionId = "session-123";

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getSessionDetails(sessionId);

    // Then
    assertThat(result).isEmpty();
  }

  // getWorkflow tests

  @Test
  void getWorkflow_validRequest_returnsWorkflowData() {
    // Given
    String sessionId = "session-123";
    String workflowId = "workflow-456";
    Map<String, Object> workflowData =
        Map.of(
            "workflowId",
            "workflow-456",
            "description",
            "Test Workflow",
            "nodes",
            List.of(),
            "edges",
            List.of());
    ApiResponse<Map<String, Object>> response =
        ApiResponse.success(200, "Workflow retrieved", workflowData);

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(response));

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getWorkflow(sessionId, workflowId);

    // Then
    assertThat(result).isEqualTo(workflowData);
  }

  @Test
  void getWorkflow_nullResponse_returnsEmptyMap() {
    // Given
    String sessionId = "session-123";
    String workflowId = "workflow-456";

    WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.empty());

    WebClient.RequestHeadersUriSpec uriSpec =
        mock(WebClient.RequestHeadersUriSpec.class, withSettings().lenient());
    when(uriSpec.uri(anyString(), any(Object[].class))).thenAnswer(inv -> uriSpec);
    when(uriSpec.retrieve()).thenReturn(responseSpec);
    when(mockWebClient.get()).thenReturn(uriSpec);

    // When
    Map<String, Object> result = client.getWorkflow(sessionId, workflowId);

    // Then
    assertThat(result).isEmpty();
  }
}
