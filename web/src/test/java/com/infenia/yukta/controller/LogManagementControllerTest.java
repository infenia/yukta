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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.LogRetrievalService;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Tests for LogManagementController. */
@NoArgsConstructor
@SuppressWarnings("PMD.LawOfDemeter")
class LogManagementControllerTest {

  /** Session identifier for testing. */
  private static final String SESSION_ID = "sess-1";

  /** Test log file name. */
  private static final String TEST_LOG = "test.log";

  /** Sample log content. */
  private static final String LOG_CONTENT = "content";

  /** API logs endpoint path. */
  private static final String API_LOGS_ENDPOINT = "/api/logs/";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webClient;

  /** Mock service for log retrieval operations. */
  private LogRetrievalService logs;

  @BeforeEach
  void setUp() {
    logs = mock(LogRetrievalService.class);
    final LogManagementController controller = new LogManagementController(logs);
    webClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  void testListLogs() {
    when(logs.listLogs(SESSION_ID)).thenReturn(Mono.just(List.of(TEST_LOG)));

    final var result =
        webClient
            .get()
            .uri(API_LOGS_ENDPOINT + SESSION_ID)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data[0]")
            .isEqualTo(TEST_LOG)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetLogContent() {
    when(logs.getLogContent(SESSION_ID, TEST_LOG)).thenReturn(Mono.just(LOG_CONTENT));

    final var result =
        webClient
            .get()
            .uri(API_LOGS_ENDPOINT + SESSION_ID + "/" + TEST_LOG)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data")
            .isEqualTo(LOG_CONTENT)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetLogContentNotFound() {
    when(logs.getLogContent(SESSION_ID, TEST_LOG))
        .thenReturn(Mono.error(new java.io.IOException()));

    final var result =
        webClient
            .get()
            .uri(API_LOGS_ENDPOINT + SESSION_ID + "/" + TEST_LOG)
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }

  @Test
  void testGetRawLogContent() {
    when(logs.getLogContent(SESSION_ID, TEST_LOG)).thenReturn(Mono.just(LOG_CONTENT));

    final var result =
        webClient
            .get()
            .uri("/api/logs/" + SESSION_ID + "/" + TEST_LOG + "/raw")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .isEqualTo(LOG_CONTENT)
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
  }

  @Test
  void testGetRawLogContentNotFound() {
    when(logs.getLogContent(SESSION_ID, TEST_LOG))
        .thenReturn(Mono.error(new java.io.IOException()));

    final var result =
        webClient
            .get()
            .uri("/api/logs/" + SESSION_ID + "/" + TEST_LOG + "/raw")
            .exchange()
            .expectStatus()
            .isNotFound()
            .returnResult();
    assertThat(result.getStatus().value()).isEqualTo(404);
  }
}
