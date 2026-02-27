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

import com.infenia.yukta.service.SessionService;
import com.infenia.yukta.service.TaskTrackerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@WebFluxTest(SessionController.class)
class SessionControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private SessionService sessionService;
  @MockitoBean private TaskTrackerService trackerService;

  @Test
  void testListSessions() {
    when(sessionService.getActiveSessions()).thenReturn(Flux.just("session-1"));
    when(sessionService.getSessionConfig("session-1")).thenReturn(Mono.just(Map.of()));
    when(trackerService.getHistory("session-1")).thenReturn(List.of());

    webTestClient
        .get()
        .uri("/api/sessions")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0].sessionId")
        .isEqualTo("session-1");
  }
}
