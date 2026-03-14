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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mapper.AppConfigMapper;
import com.infenia.yukta.model.ConfigRequest;
import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.service.SessionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(ConfigController.class)
class ConfigControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private SessionService sessionService;
  @MockitoBean private AppConfigMapper configMapper;

  @Test
  void testUpdateConfig() {
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            "desc", List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())), List.of());
    ConfigRequest request =
        new ConfigRequest(
            "session-1", "desc", "initiator-1", Map.of(), "/new/path", Map.of("w1", workflow));

    when(sessionService.applyConfigOverrides(any())).thenReturn(Mono.empty());

    webTestClient
        .post()
        .uri("/api/config")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo(200)
        .jsonPath("$.message")
        .isEqualTo("Configuration updated successfully");

    verify(configMapper).toData(any());
    verify(sessionService).applyConfigOverrides(any());
  }
}
