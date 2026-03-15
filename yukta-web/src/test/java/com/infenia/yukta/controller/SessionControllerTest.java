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

import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.SessionService;
import com.infenia.yukta.service.TaskTrackerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(SessionController.class)
class SessionControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private SessionService sessionService;
  @MockitoBean private TaskTrackerService trackerService;

  @Test
  void testGetSessionDetails() {
    Map<String, Object> config = Map.of("workflows", Map.of("wf1", Map.of()));
    when(sessionService.getSessionConfig("s1")).thenReturn(Mono.just(config));

    webTestClient
        .get()
        .uri("/api/sessions/s1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.workflowIds[0]")
        .isEqualTo("wf1");

    when(sessionService.getSessionConfig("unknown")).thenReturn(Mono.empty());
    webTestClient.get().uri("/api/sessions/unknown").exchange().expectStatus().isNotFound();
  }

  @Test
  void testGetWorkflow() {
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(), List.of());
    when(sessionService.getSessionWorkflow("s1", "wf1")).thenReturn(Mono.just(def));

    webTestClient
        .get()
        .uri("/api/sessions/s1/workflows/wf1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.description")
        .isEqualTo("desc");

    when(sessionService.getSessionWorkflow("s1", "unknown")).thenReturn(Mono.empty());
    webTestClient
        .get()
        .uri("/api/sessions/s1/workflows/unknown")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  private WebTestClient webClient() {
    return webTestClient;
  }
}
