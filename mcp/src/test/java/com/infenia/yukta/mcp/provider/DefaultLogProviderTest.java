// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class DefaultLogProviderTest {

  private static final String SESSION = "sess-1";
  private static final String EXECUTION = "exec-1";

  private DefaultLogProvider provider;
  private PluginLogStore logStore;
  private ControlBusGateway controlBus;

  @BeforeEach
  void setUp() {
    logStore = mock(PluginLogStore.class);
    controlBus = mock(ControlBusGateway.class);
    provider = new DefaultLogProvider(logStore, controlBus);
    when(controlBus.getCurrentProgress(EXECUTION))
        .thenReturn(
            new WorkflowProgress(
                EXECUTION,
                SESSION,
                "wf-1",
                "RUNNING",
                List.of(),
                LocalDateTime.now(ZoneId.systemDefault()),
                null));
  }

  private PluginLogEntry entry(final String message) {
    return new PluginLogEntry(
        EXECUTION,
        SESSION,
        "plugin-1",
        "Test Plugin",
        LogStream.STDOUT,
        message,
        LogLevel.INFO,
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        Map.of());
  }

  @Test
  void testReturnsAllFormattedLines() {
    when(logStore.readExecution(EXECUTION))
        .thenReturn(Flux.just(entry("first line"), entry("second line")));

    StepVerifier.create(provider.getExecutionLogs(SESSION, EXECUTION, null, null))
        .assertNext(
            logs -> {
              assertThat(logs.executionId()).isEqualTo(EXECUTION);
              assertThat(logs.totalLines()).isEqualTo(2);
              assertThat(logs.returnedLines()).isEqualTo(2);
              assertThat(logs.lines()).hasSize(2);
              assertThat(logs.lines().get(0)).contains("first line");
              assertThat(logs.lines().get(1)).contains("second line");
            })
        .verifyComplete();
  }

  @Test
  void testFilterPattern() {
    when(logStore.readExecution(EXECUTION))
        .thenReturn(Flux.just(entry("build started"), entry("ERROR: boom"), entry("build done")));

    StepVerifier.create(provider.getExecutionLogs(SESSION, EXECUTION, null, "ERROR"))
        .assertNext(
            logs -> {
              assertThat(logs.totalLines()).isEqualTo(1);
              assertThat(logs.returnedLines()).isEqualTo(1);
              assertThat(logs.lines().get(0)).contains("ERROR: boom");
            })
        .verifyComplete();
  }

  @Test
  void testTailLines() {
    when(logStore.readExecution(EXECUTION))
        .thenReturn(Flux.just(entry("one"), entry("two"), entry("three")));

    StepVerifier.create(provider.getExecutionLogs(SESSION, EXECUTION, 2, null))
        .assertNext(
            logs -> {
              assertThat(logs.totalLines()).isEqualTo(3);
              assertThat(logs.returnedLines()).isEqualTo(2);
              assertThat(logs.lines().get(0)).contains("two");
              assertThat(logs.lines().get(1)).contains("three");
            })
        .verifyComplete();
  }

  @Test
  void testTailZeroReturnsAllLines() {
    when(logStore.readExecution(EXECUTION)).thenReturn(Flux.just(entry("one"), entry("two")));

    StepVerifier.create(provider.getExecutionLogs(SESSION, EXECUTION, 0, null))
        .assertNext(logs -> assertThat(logs.returnedLines()).isEqualTo(2))
        .verifyComplete();
  }

  @Test
  void testTailLargerThanSizeReturnsAllLines() {
    when(logStore.readExecution(EXECUTION)).thenReturn(Flux.just(entry("one"), entry("two")));

    StepVerifier.create(provider.getExecutionLogs(SESSION, EXECUTION, 10, null))
        .assertNext(
            logs -> {
              assertThat(logs.totalLines()).isEqualTo(2);
              assertThat(logs.returnedLines()).isEqualTo(2);
            })
        .verifyComplete();
  }

  @Test
  void testInvalidRegexRejected() {
    StepVerifier.create(provider.getExecutionLogs(SESSION, EXECUTION, null, "[unclosed"))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Invalid regex pattern"))
        .verify();
  }

  @Test
  void testUnknownExecutionRejected() {
    when(controlBus.getCurrentProgress("missing")).thenReturn(null);

    StepVerifier.create(provider.getExecutionLogs(SESSION, "missing", null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: missing"))
        .verify();
  }

  @Test
  void testForeignSessionRejected() {
    StepVerifier.create(provider.getExecutionLogs("other-session", EXECUTION, null, null))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Execution not found: " + EXECUTION))
        .verify();
  }
}
