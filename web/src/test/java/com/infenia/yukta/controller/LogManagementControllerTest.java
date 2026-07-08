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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for {@link LogManagementController}'s reactive log-streaming behavior. */
@NoArgsConstructor
class LogManagementControllerTest {

  /** Test execution ID. */
  private static final String EXEC_ID = "exec-123";

  /** Test session ID. */
  private static final String SESSION_ID = "session-456";

  /** Mock control bus gateway for testing. */
  private ControlBusGateway mockControlBus;

  /** Mock plugin log store for testing. */
  private PluginLogStore mockLogStore;

  /** Controller under test. */
  private LogManagementController controller;

  @BeforeEach
  void setUp() {
    mockControlBus = mock(ControlBusGateway.class);
    mockLogStore = mock(PluginLogStore.class);
    controller = new LogManagementController(mockControlBus, mockLogStore);
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
    return new PluginLogEntry(
        EXEC_ID,
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
}
