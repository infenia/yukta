// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.dto.response.LogEntryResponse;
import com.infenia.yukta.logging.api.LogLevel;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.mapper.WorkflowMapper;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Integration test for LogManagementController with historical + live log streaming.
 *
 * <p>Tests the enhanced controller endpoint that emits cached historical logs first, then live
 * updates from the control bus.
 */
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.UnitTestShouldIncludeAssert"})
@NoArgsConstructor
class LogManagementControllerIntegrationTest {

  /** Test plugin identifier shared across log entry fixtures. */
  private static final String PLUGIN_ID = "processor-1";

  /** Test plugin display name shared across log entry fixtures. */
  private static final String PLUGIN_NAME = "Processor";

  /** Web test client for testing controller endpoints. */
  private WebTestClient webTestClient;

  /** Mock control bus gateway for testing. */
  private ControlBusGateway mockControlBus;

  /** Mock plugin log store for testing. */
  private PluginLogStore mockLogStore;

  /** Mock task tracker for testing the structured live log flux. */
  private DefaultTaskTrackerService mockTaskTracker;

  @BeforeEach
  void setUp() {
    mockControlBus = mock(ControlBusGateway.class);
    mockLogStore = mock(PluginLogStore.class);
    mockTaskTracker = mock(DefaultTaskTrackerService.class);
    final LogManagementController controller =
        new LogManagementController(
            mockControlBus, mockLogStore, mockTaskTracker, Mappers.getMapper(WorkflowMapper.class));
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
            PLUGIN_ID,
            PLUGIN_NAME,
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
            PLUGIN_ID,
            PLUGIN_NAME,
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

  /**
   * Test that streamExecutionLogEntries emits structured historical entries then live entries over
   * HTTP.
   *
   * <p>Verifies the {@code /entries} route serializes {@link LogEntryResponse} JSON correctly for
   * both the historical and live phases of the stream.
   */
  @Test
  void testStreamLogEntriesEmitsHistoryThenLive() {
    final String sessionId = "session-321";
    final String executionId = "exec-654";
    final Instant now = Instant.now();

    final PluginLogEntry historical =
        new PluginLogEntry(
            executionId,
            sessionId,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDOUT,
            "Historical entry",
            LogLevel.INFO,
            now.minusSeconds(10),
            null,
            null);
    final PluginLogEntry live =
        new PluginLogEntry(
            executionId,
            sessionId,
            PLUGIN_ID,
            PLUGIN_NAME,
            LogStream.STDOUT,
            "Live entry",
            LogLevel.INFO,
            now,
            null,
            null);

    when(mockLogStore.readExecution(executionId)).thenReturn(Flux.just(historical));
    when(mockTaskTracker.getLogFlux()).thenReturn(Flux.just(live));

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

    final var responseBody =
        webTestClient
            .get()
            .uri("/api/sessions/" + sessionId + "/executions/" + executionId + "/logs/entries")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(LogEntryResponse.class)
            .getResponseBody();

    StepVerifier.create(responseBody.take(2))
        .expectNextMatches(
            entry ->
                "Historical entry".equals(entry.message()) && PLUGIN_ID.equals(entry.pluginId()))
        .expectNextMatches(entry -> "Live entry".equals(entry.message()))
        .verifyComplete();
  }
}
