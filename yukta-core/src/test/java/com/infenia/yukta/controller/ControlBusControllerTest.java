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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.service.ControlBusService;
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
  private ControlBusService controlBusService;

  @BeforeEach
  void setUp() {
    controlBusService = mock(ControlBusService.class);
    ControlBusController controller = new ControlBusController(controlBusService);
    webClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  void testGetActiveNodes() {
    when(controlBusService.getActiveNodes()).thenReturn(List.of("node1", "node2"));
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
  @SuppressWarnings("unchecked")
  void testGetLastHeartbeat() {
    Message hb = DefaultMessage.create(null, "ok").withControl(true);
    when(controlBusService.getLastHeartbeat("n1")).thenReturn(hb);

    webClient
        .get()
        .uri("/api/control/nodes/n1/heartbeat")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.payload")
        .isEqualTo("ok");
  }

  @Test
  @SuppressWarnings("unchecked")
  void testSendCommand() {
    Message resp = DefaultMessage.create(null, "done");
    when(controlBusService.sendCommand(eq("n1"), any())).thenReturn(Mono.just(resp));

    webClient
        .post()
        .uri("/api/control/nodes/n1/command")
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
  @SuppressWarnings("unchecked")
  void testStreamControlSignals() {
    Message m1 = DefaultMessage.create(null, "s1");
    when(controlBusService.getControlStream()).thenReturn(Flux.just(m1));

    webClient
        .get()
        .uri("/api/control/stream")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
  }
}
