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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.LogRetrievalService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class LogManagementControllerTest {

  private WebTestClient webClient;
  private LogRetrievalService logs;

  @BeforeEach
  void setUp() {
    logs = mock(LogRetrievalService.class);
    LogManagementController controller = new LogManagementController(logs);
    webClient = WebTestClient.bindToController(controller).build();
  }

  @Test
  void testListLogs() {
    when(logs.listLogs("sess-1")).thenReturn(Mono.just(List.of("test.log")));

    webClient
        .get()
        .uri("/api/logs/sess-1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data[0]")
        .isEqualTo("test.log");
  }

  @Test
  void testGetLogContent() {
    when(logs.getLogContent("sess-1", "test.log")).thenReturn(Mono.just("content"));

    webClient
        .get()
        .uri("/api/logs/sess-1/test.log")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data")
        .isEqualTo("content");
  }

  @Test
  void testGetLogContentNotFound() {
    when(logs.getLogContent("sess-1", "test.log"))
        .thenReturn(Mono.error(new java.io.IOException()));

    webClient.get().uri("/api/logs/sess-1/test.log").exchange().expectStatus().isNotFound();
  }

  @Test
  void testGetRawLogContent() {
    when(logs.getLogContent("sess-1", "test.log")).thenReturn(Mono.just("content"));

    webClient
        .get()
        .uri("/api/logs/sess-1/test.log/raw")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .isEqualTo("content");
  }

  @Test
  void testGetRawLogContentNotFound() {
    when(logs.getLogContent("sess-1", "test.log"))
        .thenReturn(Mono.error(new java.io.IOException()));

    webClient.get().uri("/api/logs/sess-1/test.log/raw").exchange().expectStatus().isNotFound();
  }
}
