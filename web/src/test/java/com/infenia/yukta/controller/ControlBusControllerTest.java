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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ControlBusControllerTest {

  private WebTestClient webClient;
  private ControlBusGateway controlBusGateway;

  @BeforeEach
  void setUp() {
    controlBusGateway = mock(ControlBusGateway.class);
    ControlBusController controller = new ControlBusController(controlBusGateway);
    webClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  void testGetActiveNodes() {
    when(controlBusGateway.getActiveNodes()).thenReturn(List.of("node1", "node2"));
    webClient
        .get()
        .uri("/api/control/nodes")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0]")
        .isEqualTo("node1")
        .jsonPath("$.message")
        .isEqualTo("Active nodes retrieved");
  }

  @Test
  void testGetLastHeartbeat() {
    final Message<?> hb = DefaultMessage.create(null, "ok").withControl(true);
    doReturn(hb).when(controlBusGateway).getLastHeartbeat("wf1", "n1");

    webClient
        .get()
        .uri("/api/control/workflows/wf1/nodes/n1/heartbeat")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.payload")
        .isEqualTo("ok");
  }

  @Test
  void testSendCommand() {
    final Message<?> resp = DefaultMessage.create(null, "done");
    when(controlBusGateway.sendCommand(eq("wf1"), eq("n1"), any())).thenReturn(Mono.just(resp));

    webClient
        .post()
        .uri("/api/control/workflows/wf1/nodes/n1/command")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("cmd", "reset"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Command processed");
  }

  @Test
  void testStreamProgress() {
    WorkflowProgress progress = mock(WorkflowProgress.class);
    when(controlBusGateway.watchExecution("exec1")).thenReturn(Flux.just(progress));

    webClient
        .get()
        .uri("/api/control/executions/exec1/progress/stream")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
  }

  @Test
  void testGetActiveNodesInWorkflow() {
    when(controlBusGateway.getActiveNodes("wf1")).thenReturn(List.of("node1", "node2"));
    webClient
        .get()
        .uri("/api/control/workflows/wf1/nodes")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0]")
        .isEqualTo("node1")
        .jsonPath("$.message")
        .isEqualTo("Active nodes retrieved");
  }

  @Test
  void testGetProgress() {
    WorkflowProgress progress = mock(WorkflowProgress.class);
    when(controlBusGateway.getCurrentProgress("exec1")).thenReturn(progress);

    webClient
        .get()
        .uri("/api/control/executions/exec1/progress")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("Progress retrieved");
  }

  @Test
  void testStreamLogs() {
    when(controlBusGateway.watchLogs("exec1")).thenReturn(Flux.just("log1", "log2"));

    webClient
        .get()
        .uri("/api/control/executions/exec1/logs/stream")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
  }

  @Test
  void testGetHistory() {
    when(controlBusGateway.getHistory("session1")).thenReturn(List.of());

    webClient
        .get()
        .uri("/api/control/sessions/session1/history")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.message")
        .isEqualTo("History retrieved");
  }
}
