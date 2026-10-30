// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.exception.GlobalExceptionHandler;
import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.mapper.WorkflowMapper;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Tests for the behaviour described by {@link LogManagementController}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.UnitTestShouldIncludeAssert",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods"
})
class LogManagementControllerTest {

  /** The control bus. */
  private ControlBusGateway controlBus;

  /** The log store. */
  private PluginLogStore logStore;

  /** The tracker. */
  private DefaultTaskTrackerService tracker;

  /** The client. */
  private WebTestClient client;

  private static PluginLogEntry entry(final String message, final Instant at) {
    return new PluginLogEntry(
        "e1", "s1", "p1", "Plugin", LogStream.STDOUT, message, LogLevel.INFO, at, null, Map.of());
  }

  private static WorkflowProgress progress(final String status) {
    return new WorkflowProgress(
        "e1", "s1", "wf", status, List.of(), LocalDateTime.now(), null, null);
  }

  @BeforeEach
  void setUp() {
    controlBus = mock(ControlBusGateway.class);
    logStore = mock(PluginLogStore.class);
    tracker = mock(DefaultTaskTrackerService.class);
    client =
        WebTestClient.bindToController(
                new LogManagementController(
                    controlBus,
                    logStore,
                    tracker,
                    Mappers.getMapper(WorkflowMapper.class),
                    new ExecutionAccess(controlBus)))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private String lines(final String uri) {
    return String.join(
        "\n",
        client
            .get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .block());
  }

  @Test
  void testFinishedExecutionStreamsStoredHistoryWithIds() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("SUCCESS"));
    final Instant now = Instant.now();
    when(logStore.readExecution("e1")).thenReturn(Flux.just(entry("one", now), entry("two", now)));

    final String body = lines("/api/sessions/s1/executions/e1/logs");

    assertThat(body).contains("one").contains("two");
  }

  @Test
  void testLastEventIdResumesAfterTheLastSeenLine() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("SUCCESS"));
    final Instant now = Instant.now();
    when(logStore.readExecution("e1"))
        .thenReturn(Flux.just(entry("one", now), entry("two", now), entry("three", now)));

    final String body =
        String.join(
            "\n",
            client
                .get()
                .uri("/api/sessions/s1/executions/e1/logs")
                .header("Last-Event-ID", "1")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block());

    assertThat(body).contains("three").doesNotContain("one").doesNotContain("two");
  }

  @Test
  void testRunningExecutionGetsHistoryThenLiveWithoutDuplicatesOrGaps() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("RUNNING"));
    final Sinks.Many<PluginLogEntry> live = Sinks.many().multicast().directBestEffort();
    when(tracker.getLogFlux()).thenReturn(live.asFlux());
    final Sinks.Many<WorkflowProgress> status = Sinks.many().multicast().directBestEffort();
    when(controlBus.watchExecution("e1", true)).thenReturn(status.asFlux());
    final Instant before = Instant.now().minusSeconds(60);
    // "seam" is written during the history read: it appears in the store and on the live feed.
    when(logStore.readExecution("e1"))
        .thenReturn(
            Flux.defer(
                () -> {
                  final Instant seamAt = Instant.now();
                  live.tryEmitNext(entry("seam", seamAt));
                  return Flux.just(entry("old", before), entry("seam", seamAt));
                }));

    final var result =
        client
            .get()
            .uri("/api/sessions/s1/executions/e1/logs")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody();
    final var collected = result.collectList().toFuture();
    live.tryEmitNext(entry("fresh", Instant.now().plusSeconds(5)));
    status.tryEmitNext(progress("RUNNING"));
    status.tryEmitNext(progress("SUCCESS"));
    status.tryEmitNext(
        new WorkflowProgress(
            "e1",
            "s1",
            "wf",
            "SUCCESS",
            List.of(),
            LocalDateTime.now(),
            LocalDateTime.now(),
            null));

    final String body = String.join("\n", collected.join());
    assertThat(body).contains("old").contains("fresh");
    assertThat(body.split("seam", -1).length - 1).isEqualTo(1);
  }

  private String logsWithLiveEntries(
      final java.util.function.Consumer<Sinks.Many<PluginLogEntry>> afterSubscribe) {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("RUNNING"));
    final Sinks.Many<PluginLogEntry> live = Sinks.many().multicast().directBestEffort();
    when(tracker.getLogFlux()).thenReturn(live.asFlux());
    final Sinks.Many<WorkflowProgress> status = Sinks.many().multicast().directBestEffort();
    when(controlBus.watchExecution("e1", true)).thenReturn(status.asFlux());
    when(logStore.readExecution("e1"))
        .thenReturn(Flux.defer(() -> Flux.just(entry("seam", Instant.now()))));

    final var collected =
        client
            .get()
            .uri("/api/sessions/s1/executions/e1/logs")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .collectList()
            .toFuture();
    afterSubscribe.accept(live);
    status.tryEmitNext(
        new WorkflowProgress(
            "e1",
            "s1",
            "wf",
            "SUCCESS",
            List.of(),
            LocalDateTime.now(),
            LocalDateTime.now(),
            null));
    return String.join("\n", collected.join());
  }

  @Test
  void testLiveRepeatOfHistoryEntryIsDroppedOnceThenSent() {
    final String body =
        logsWithLiveEntries(
            live -> {
              live.tryEmitNext(entry("seam", Instant.now().minusSeconds(60)));
              live.tryEmitNext(entry("seam", Instant.now().minusSeconds(60)));
            });

    assertThat(body.split("seam", -1).length - 1).isEqualTo(2);
  }

  @Test
  void testLaterLiveEntryWithSameTextAsHistoryEntryIsStillSent() {
    final String body =
        logsWithLiveEntries(live -> live.tryEmitNext(entry("seam", Instant.now().plusSeconds(60))));

    assertThat(body.split("seam", -1).length - 1).isEqualTo(2);
  }

  @Test
  void testForeignOrUnknownExecutionIs404() {
    client
        .get()
        .uri("/api/sessions/s1/executions/ghost/logs")
        .exchange()
        .expectStatus()
        .isNotFound();
    client
        .get()
        .uri("/api/sessions/s1/executions/ghost/logs/entries")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  @Test
  void testStructuredEntries() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("FAILURE"));
    when(logStore.readExecution("e1")).thenReturn(Flux.just(entry("boom", Instant.now())));

    assertThat(lines("/api/sessions/s1/executions/e1/logs/entries")).contains("boom");
  }

  @Test
  void testRepeatedLineAfterTheSnapshotIsNotSuppressedAndCancelDisposesLiveFeed() {
    when(controlBus.getProgress("s1", "e1")).thenReturn(progress("RUNNING"));
    final Sinks.Many<PluginLogEntry> live = Sinks.many().replay().all();
    when(tracker.getLogFlux()).thenReturn(live.asFlux());
    when(controlBus.watchExecution("e1", true)).thenReturn(Flux.never());
    final Instant now = Instant.now();
    when(logStore.readExecution("e1")).thenReturn(Flux.just(entry("tick", now)));
    live.tryEmitNext(entry("tick", Instant.now().plusSeconds(30)));

    final var lines =
        client
            .get()
            .uri("/api/sessions/s1/executions/e1/logs")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .take(2)
            .collectList()
            .block();

    assertThat(lines).hasSize(2);
  }
}
