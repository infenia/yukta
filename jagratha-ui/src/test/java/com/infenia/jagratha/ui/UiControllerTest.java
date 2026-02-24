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
package com.infenia.jagratha.ui;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(UiController.class)
class UiControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private SessionService sessionService;
  @MockitoBean private LogRetrievalService logRetrievalService;

  @MockitoBean private TaskTrackerService taskTrackerService;

  @MockitoBean private gg.jte.TemplateEngine templateEngine;

  @Test
  void testStreamLogs() {
    when(taskTrackerService.getLogStream(anyString(), anyString())).thenReturn(Flux.empty());
    webTestClient
        .get()
        .uri("/ui/api/sessions/session1/wf1/logs/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith("text/event-stream");
  }

  @Test
  void testStreamStatus() {
    when(taskTrackerService.getStatusStream(anyString(), anyString())).thenReturn(Flux.empty());
    webTestClient
        .get()
        .uri("/ui/api/sessions/session1/wf1/status/stream")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .contentTypeCompatibleWith("text/event-stream");
  }

  @Test
  void testIndex() {
    when(sessionService.getActiveSessions()).thenReturn(Flux.empty());
    webTestClient.get().uri("/ui").exchange().expectStatus().isOk();
  }

  @Test
  void testHistory() {
    when(sessionService.getHistorySessions()).thenReturn(Flux.empty());
    webTestClient.get().uri("/ui/history").exchange().expectStatus().isOk();
  }

  @Test
  void testSessionDetails() {
    when(sessionService.getSessionConfig(anyString())).thenReturn(Mono.just(Map.of()));
    when(sessionService.getSessionWorkflow(anyString(), anyString())).thenReturn(Mono.empty());
    when(logRetrievalService.listLogs(anyString())).thenReturn(Mono.just(List.of()));
    webTestClient.get().uri("/ui/sessions/session1").exchange().expectStatus().isOk();
  }
}
