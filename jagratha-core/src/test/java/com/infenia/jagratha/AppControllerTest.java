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
package com.infenia.jagratha;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.controller.AppController;
import com.infenia.jagratha.mapper.AppConfigMapper;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.FileRequest;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowTriggerRequest;
import com.infenia.jagratha.service.FileLogService;
import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.WorkflowService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(AppController.class)
class AppControllerTest {

  @Autowired private WebTestClient webTestClient;

  @MockitoBean private FileLogService fileLogService;
  @MockitoBean private WorkflowService workflowService;
  @MockitoBean private SessionService sessionService;
  @MockitoBean private LogRetrievalService logRetrievalService;
  @MockitoBean private AppConfigMapper configMapper;

  @Test
  void testSaveFile() {
    FileRequest request = new FileRequest("src/Test.java", "session-1", "content");

    when(fileLogService.saveFile(anyString(), anyString())).thenReturn(Mono.empty());

    webTestClient
        .post()
        .uri("/api/files")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("File path logged successfully");
  }

  @Test
  void testTriggerWorkflowSuccess() {
    WorkflowTriggerRequest request = new WorkflowTriggerRequest("session-1");
    TaskResponse response = new TaskResponse("SUCCESS", "Build successful");

    when(workflowService.runQualityChecks(anyString())).thenReturn(Mono.just(response));

    webTestClient
        .post()
        .uri("/api/workflow/trigger")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("SUCCESS")
        .jsonPath("$.output")
        .isEqualTo("Build successful");
  }

  @Test
  void testUpdateConfig() {
    WorkflowDefinition workflow =
        new WorkflowDefinition(
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())), List.of());
    ConfigRequest request = new ConfigRequest("session-1", "/new/path", workflow);

    when(sessionService.applyConfigOverrides(any())).thenReturn(Mono.empty());

    webTestClient
        .post()
        .uri("/api/config")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("Configuration updated successfully");

    verify(configMapper).toData(any());
    verify(sessionService).applyConfigOverrides(any());
  }
}
