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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Tests for ControlBusController. */
@NoArgsConstructor
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.TooManyStaticImports"})
class ControlBusControllerTest {

  /** First test node identifier. */
  private static final String NODE1 = "node1";

  /** Second test node identifier. */
  private static final String NODE2 = "node2";

  /** JSONPath for response message field. */
  private static final String MESSAGE_PATH = "$.message";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webClient;

  /** Mock gateway for control bus operations. */
  private ControlBusGateway controlBusGateway;

  @BeforeEach
  void setUp() {
    controlBusGateway = mock(ControlBusGateway.class);
    final ControlBusController controller = new ControlBusController(controlBusGateway);
    webClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  void testGetActiveNodes() {
    when(controlBusGateway.getActiveNodes()).thenReturn(List.of(NODE1, NODE2));
    final var result =
        webClient
            .get()
            .uri("/api/control/nodes")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data[0]")
            .isEqualTo(NODE1)
            .jsonPath(MESSAGE_PATH)
            .isEqualTo("Active nodes retrieved")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetLastHeartbeat() {
    final Message<?> healthBytes = DefaultMessage.create(null, "ok").withControl(true);
    doReturn(healthBytes).when(controlBusGateway).getLastHeartbeat("wf1", "n1");

    final var result =
        webClient
            .get()
            .uri("/api/control/workflows/wf1/nodes/n1/heartbeat")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data.payload")
            .isEqualTo("ok")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testSendCommand() {
    final Message<?> resp = DefaultMessage.create(null, "done");
    when(controlBusGateway.sendCommand(eq("wf1"), eq("n1"), any())).thenReturn(Mono.just(resp));

    final var result =
        webClient
            .post()
            .uri("/api/control/workflows/wf1/nodes/n1/command")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("cmd", "reset"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(MESSAGE_PATH)
            .isEqualTo("Command processed")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStreamProgress() {
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(controlBusGateway.watchExecution("exec1")).thenReturn(Flux.just(progress));

    final var result =
        webClient
            .get()
            .uri("/api/control/executions/exec1/progress/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetActiveNodesInWorkflow() {
    when(controlBusGateway.getActiveNodes("wf1")).thenReturn(List.of(NODE1, NODE2));
    final var result =
        webClient
            .get()
            .uri("/api/control/workflows/wf1/nodes")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data[0]")
            .isEqualTo(NODE1)
            .jsonPath(MESSAGE_PATH)
            .isEqualTo("Active nodes retrieved")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetProgress() {
    final WorkflowProgress progress = mock(WorkflowProgress.class);
    when(controlBusGateway.getCurrentProgress("exec1")).thenReturn(progress);

    final var result =
        webClient
            .get()
            .uri("/api/control/executions/exec1/progress")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(MESSAGE_PATH)
            .isEqualTo("Progress retrieved")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testStreamLogs() {
    when(controlBusGateway.watchLogs("exec1")).thenReturn(Flux.just("log1", "log2"));

    final var result =
        webClient
            .get()
            .uri("/api/control/executions/exec1/logs/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetHistory() {
    when(controlBusGateway.getHistory("session1")).thenReturn(List.of());

    final var result =
        webClient
            .get()
            .uri("/api/control/sessions/session1/history")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath(MESSAGE_PATH)
            .isEqualTo("History retrieved")
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }
}
