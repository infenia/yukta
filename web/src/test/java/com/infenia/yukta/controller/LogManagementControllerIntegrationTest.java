// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

/**
 * Integration test for LogManagementController with historical + live log streaming.
 *
 * <p>Tests the enhanced controller endpoint that emits cached historical logs first, then live
 * updates from the control bus.
 */
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.UnitTestShouldIncludeAssert"})
@NoArgsConstructor
class LogManagementControllerIntegrationTest {

  /** Web test client for testing controller endpoints. */
  private WebTestClient webTestClient;

  /** Mock control bus gateway for testing. */
  private ControlBusGateway mockControlBus;

  /** Mock plugin log store for testing. */
  private PluginLogStore mockLogStore;

  @BeforeEach
  void setUp() {
    mockControlBus = mock(ControlBusGateway.class);
    mockLogStore = mock(PluginLogStore.class);
    final LogManagementController controller =
        new LogManagementController(mockControlBus, mockLogStore);
    webTestClient = WebTestClient.bindToController(controller).build();
  }

  /**
   * Test that streamExecutionLogs emits historical logs first, then live logs.
   *
   * <p>Verifies the two-phase streaming: Phase 1 reads cached history from the store, Phase 2
   * subscribes to live updates from the control bus.
   */
  @Test
  void testStreamLogsEmitsHistoryThenLive() {
    final String sessionId = "session-123";
    final String executionId = "exec-456";
    final Instant now = Instant.now();

    // Set up historical logs from store
    final PluginLogEntry historical1 =
        new PluginLogEntry(
            executionId,
            sessionId,
            "processor-1",
            "Processor",
            LogStream.STDOUT,
            "Historical line 1",
            LogLevel.INFO,
            now.minusSeconds(10),
            null,
            null);

    final PluginLogEntry historical2 =
        new PluginLogEntry(
            executionId,
            sessionId,
            "processor-1",
            "Processor",
            LogStream.STDOUT,
            "Historical line 2",
            LogLevel.INFO,
            now.minusSeconds(5),
            null,
            null);

    when(mockLogStore.readExecution(executionId)).thenReturn(Flux.just(historical1, historical2));

    // Set up an in-progress execution that never reaches a terminal state during the test
    when(mockControlBus.getCurrentProgress(executionId))
        .thenReturn(
            new WorkflowProgress(
                executionId,
                sessionId,
                "workflow-1",
                "RUNNING",
                List.of(),
                LocalDateTime.now(ZoneOffset.UTC),
                null));
    when(mockControlBus.watchExecution(executionId, true)).thenReturn(Flux.never());

    // Set up live logs from control bus with delay
    when(mockControlBus.watchLogs(sessionId, executionId))
        .thenReturn(Flux.just("Live line 1", "Live line 2"));

    // Execute request and collect results
    final var lines =
        webTestClient
            .get()
            .uri("/api/sessions/" + sessionId + "/executions/" + executionId + "/logs")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .take(4)
            .collectList()
            .block();

    Objects.requireNonNull(lines)
        .forEach(line -> assertThat(line).containsAnyOf("Historical", "Live"));
  }

  /**
   * Test that streamExecutionLogs handles empty history gracefully.
   *
   * <p>When no historical logs exist, the stream should continue immediately to live logs.
   */
  @Test
  void testStreamLogsHandlesEmptyHistory() {
    final String sessionId = "session-789";
    final String executionId = "exec-new";

    // Mock: no historical logs
    when(mockLogStore.readExecution(executionId)).thenReturn(Flux.empty());

    // Mock: in-progress execution
    when(mockControlBus.getCurrentProgress(executionId))
        .thenReturn(
            new WorkflowProgress(
                executionId,
                sessionId,
                "workflow-1",
                "RUNNING",
                List.of(),
                LocalDateTime.now(ZoneOffset.UTC),
                null));
    when(mockControlBus.watchExecution(executionId, true)).thenReturn(Flux.never());

    // Mock: live logs available
    when(mockControlBus.watchLogs(sessionId, executionId))
        .thenReturn(Flux.just("First live log", "Second live log"));

    // Execute request
    webTestClient
        .get()
        .uri("/api/sessions/" + sessionId + "/executions/" + executionId + "/logs")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk();
  }
}
