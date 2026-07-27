// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for {@link LogManagementController}'s reactive log-streaming behavior. */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class LogManagementControllerTest {

  /** Test execution ID. */
  private static final String EXEC_ID = "exec-123";

  /** Test session ID. */
  private static final String SESSION_ID = "session-456";

  /** Other execution ID used for live-entry filtering. */
  private static final String OTHER_EXEC_ID = "exec-other";

  /** Mock control bus gateway for testing. */
  private ControlBusGateway mockControlBus;

  /** Mock plugin log store for testing. */
  private PluginLogStore mockLogStore;

  /** Mock task tracker for the structured live log stream. */
  private DefaultTaskTrackerService mockTaskTracker;

  /** Controller under test. */
  private LogManagementController controller;

  @BeforeEach
  void setUp() {
    mockControlBus = mock(ControlBusGateway.class);
    mockLogStore = mock(PluginLogStore.class);
    mockTaskTracker = mock(DefaultTaskTrackerService.class);
    controller =
        new LogManagementController(
            mockControlBus, mockLogStore, mockTaskTracker, Mappers.getMapper(WorkflowMapper.class));
  }

  private static WorkflowProgress progressWithStatus(final String status, final Instant endTime) {
    return new WorkflowProgress(
        EXEC_ID,
        SESSION_ID,
        "workflow-1",
        status,
        List.of(),
        LocalDateTime.now(ZoneOffset.UTC),
        endTime == null ? null : LocalDateTime.ofInstant(endTime, ZoneOffset.UTC));
  }

  private static PluginLogEntry historicalEntry(final String message) {
    return logEntry(EXEC_ID, message);
  }

  private static PluginLogEntry logEntry(final String executionId, final String message) {
    return new PluginLogEntry(
        executionId,
        SESSION_ID,
        "processor-1",
        "Processor",
        LogStream.STDOUT,
        message,
        LogLevel.INFO,
        Instant.now(),
        null,
        null);
  }

  @Test
  void streamsHistoricalLogsOnlyWhenExecutionAlreadyTerminal() {
    when(mockControlBus.getCurrentProgress(EXEC_ID))
        .thenReturn(progressWithStatus("COMPLETED", Instant.now()));
    when(mockLogStore.readExecution(EXEC_ID))
        .thenReturn(Flux.just(historicalEntry("line 1"), historicalEntry("line 2")));

    StepVerifier.create(controller.streamExecutionLogs(SESSION_ID, EXEC_ID))
        .expectNextMatches(line -> line.contains("line 1"))
        .expectNextMatches(line -> line.contains("line 2"))
        .verifyComplete();
  }

  @Test
  void streamsHistoricalThenLiveLogsWhenExecutionInProgress() {
    when(mockControlBus.getCurrentProgress(EXEC_ID))
        .thenReturn(progressWithStatus("RUNNING", null));
    when(mockLogStore.readExecution(EXEC_ID)).thenReturn(Flux.just(historicalEntry("history 1")));
    when(mockControlBus.watchExecution(EXEC_ID, true)).thenReturn(Flux.never());
    when(mockControlBus.watchLogs(SESSION_ID, EXEC_ID)).thenReturn(Flux.just("live 1", "live 2"));

    StepVerifier.create(controller.streamExecutionLogs(SESSION_ID, EXEC_ID))
        .expectNextMatches(line -> line.contains("history 1"))
        .expectNext("live 1", "live 2")
        .verifyComplete();
  }

  @Test
  void stopsLiveLogsWhenExecutionReachesTerminalStateMidStream() {
    when(mockControlBus.getCurrentProgress(EXEC_ID))
        .thenReturn(progressWithStatus("RUNNING", null));
    when(mockLogStore.readExecution(EXEC_ID)).thenReturn(Flux.empty());
    when(mockControlBus.watchExecution(EXEC_ID, true))
        .thenReturn(
            Flux.just(
                progressWithStatus("RUNNING", null),
                progressWithStatus("FAILED", null),
                progressWithStatus("CANCELLED", Instant.now()),
                progressWithStatus("WORKFLOW_STOPPED", Instant.now())));
    when(mockControlBus.watchLogs(SESSION_ID, EXEC_ID)).thenReturn(Flux.never());

    StepVerifier.create(controller.streamExecutionLogs(SESSION_ID, EXEC_ID)).verifyComplete();
  }

  @Test
  void streamsStructuredHistoricalEntriesOnlyWhenExecutionAlreadyTerminal() {
    when(mockControlBus.getCurrentProgress(EXEC_ID))
        .thenReturn(progressWithStatus("COMPLETED", Instant.now()));
    when(mockLogStore.readExecution(EXEC_ID))
        .thenReturn(Flux.just(historicalEntry("entry 1"), historicalEntry("entry 2")));

    StepVerifier.create(controller.streamExecutionLogEntries(SESSION_ID, EXEC_ID))
        .expectNextMatches(
            entry -> "entry 1".equals(entry.message()) && "INFO".equals(entry.level()))
        .expectNextMatches(entry -> "entry 2".equals(entry.message()))
        .verifyComplete();
  }

  @Test
  void streamsStructuredHistoryThenLiveEntriesWhenExecutionInProgress() {
    when(mockControlBus.getCurrentProgress(EXEC_ID))
        .thenReturn(progressWithStatus("RUNNING", null));
    when(mockLogStore.readExecution(EXEC_ID)).thenReturn(Flux.just(historicalEntry("history 1")));
    when(mockControlBus.watchExecution(EXEC_ID, true)).thenReturn(Flux.never());
    when(mockTaskTracker.getLogFlux())
        .thenReturn(Flux.just(logEntry(EXEC_ID, "live 1"), logEntry(EXEC_ID, "live 2")));

    StepVerifier.create(controller.streamExecutionLogEntries(SESSION_ID, EXEC_ID))
        .expectNextMatches(entry -> "history 1".equals(entry.message()))
        .expectNextMatches(entry -> "live 1".equals(entry.message()))
        .expectNextMatches(entry -> "live 2".equals(entry.message()))
        .verifyComplete();
  }

  @Test
  void filtersLiveEntriesFromOtherExecutions() {
    when(mockControlBus.getCurrentProgress(EXEC_ID))
        .thenReturn(progressWithStatus("RUNNING", null));
    when(mockLogStore.readExecution(EXEC_ID)).thenReturn(Flux.empty());
    when(mockControlBus.watchExecution(EXEC_ID, true)).thenReturn(Flux.never());
    when(mockTaskTracker.getLogFlux())
        .thenReturn(
            Flux.just(
                logEntry(OTHER_EXEC_ID, "foreign"),
                logEntry(EXEC_ID, "mine"),
                logEntry(OTHER_EXEC_ID, "foreign 2")));

    StepVerifier.create(controller.streamExecutionLogEntries(SESSION_ID, EXEC_ID))
        .expectNextMatches(entry -> "mine".equals(entry.message()))
        .verifyComplete();
  }

  @Test
  void stopsStructuredLiveEntriesWhenExecutionReachesTerminalState() {
    when(mockControlBus.getCurrentProgress(EXEC_ID))
        .thenReturn(progressWithStatus("RUNNING", null));
    when(mockLogStore.readExecution(EXEC_ID)).thenReturn(Flux.empty());
    when(mockControlBus.watchExecution(EXEC_ID, true))
        .thenReturn(Flux.just(progressWithStatus("COMPLETED", Instant.now())));
    when(mockTaskTracker.getLogFlux()).thenReturn(Flux.never());

    StepVerifier.create(controller.streamExecutionLogEntries(SESSION_ID, EXEC_ID)).verifyComplete();
  }

  @Test
  void streamsHistoricalEntriesOnlyWhenExecutionIsUnknown() {
    when(mockControlBus.getCurrentProgress(EXEC_ID)).thenReturn(null);
    when(mockLogStore.readExecution(EXEC_ID)).thenReturn(Flux.just(historicalEntry("stored")));

    StepVerifier.create(controller.streamExecutionLogEntries(SESSION_ID, EXEC_ID))
        .expectNextMatches(entry -> "stored".equals(entry.message()))
        .verifyComplete();
  }
}
