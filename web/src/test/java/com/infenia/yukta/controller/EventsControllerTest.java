// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Tests for the behaviour described by {@link EventsController}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.TooManyStaticImports"})
class EventsControllerTest {

  /** Answers which runs lie within a tree, for the tree filter. */
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);

  /** Envelope type of a workflow status change. */
  private static final String WORKFLOW_STATUS = "WorkflowStatusChanged";

  @BeforeEach
  void everyRunIsItsOwnRoot() {
    when(tracker.lineage(anyString()))
        .thenAnswer(call -> new TaskTrackerService.Lineage(call.getArgument(0), 0));
  }

  private static ExecutionEvent workflow(final String executionId, final String status) {
    return new ExecutionEvent.WorkflowStatusChanged(executionId, "wf", status, Instant.now());
  }

  private static ExecutionEvent control(final String executionId) {
    return new ExecutionEvent.ControlOutcome(
        executionId, "PauseWorkflowCommand", "rules", true, null, Instant.now());
  }

  private List<EventsController.Envelope> collect(
      final ExecutionEventFeed feed,
      final String executionId,
      final String type,
      final String severity) {
    return collect(feed, executionId, false, type, severity);
  }

  private List<EventsController.Envelope> collect(
      final ExecutionEventFeed feed,
      final String executionId,
      final boolean tree,
      final String type,
      final String severity) {
    final Flux<ServerSentEvent<EventsController.Envelope>> stream =
        new EventsController(feed, tracker).stream(executionId, tree, type, severity);
    final var received =
        stream
            .filter(event -> event.data() != null)
            .map(ServerSentEvent::data)
            .take(Duration.ofMillis(500))
            .collectList()
            .toFuture();
    Flux.just(
            workflow("e1", "RUNNING"),
            control("e1"),
            workflow("e2", "RUNNING"),
            workflow("e1", "SUCCESS"),
            workflow("e3", "FAILURE"))
        .delaySubscription(Duration.ofMillis(100))
        .publishOn(Schedulers.single())
        .subscribe(feed::publish);
    return received.join();
  }

  @Test
  void stream_noFilter_deliversEveryEventWithItsType() {
    final var events = collect(new ExecutionEventFeed(), null, null, null);

    assertThat(events)
        .extracting(EventsController.Envelope::type)
        .containsExactly(
            WORKFLOW_STATUS, "ControlOutcome", WORKFLOW_STATUS, WORKFLOW_STATUS, WORKFLOW_STATUS);
  }

  @Test
  void stream_carriesTheSeverityOfEachEvent() {
    final var events = collect(new ExecutionEventFeed(), null, null, null);

    assertThat(events)
        .extracting(EventsController.Envelope::severity)
        .containsExactly(
            EventSeverity.INFO,
            EventSeverity.INFO,
            EventSeverity.INFO,
            EventSeverity.INFO,
            EventSeverity.ERROR);
  }

  @Test
  void stream_withMinimumSeverity_dropsEverythingBelowIt() {
    final var events = collect(new ExecutionEventFeed(), null, null, "warn");

    assertThat(events).extracting(envelope -> envelope.event().executionId()).containsExactly("e3");
  }

  @Test
  void stream_withUnknownSeverity_isRejected() {
    final var controller = new EventsController(new ExecutionEventFeed(), tracker);

    assertThatThrownBy(() -> controller.stream(null, false, null, "loud"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("loud");
  }

  @Test
  void stream_filteredByExecutionAndType_deliversOnlyMatches() {
    final var events = collect(new ExecutionEventFeed(), "e1", "workflowstatuschanged", null);

    assertThat(events)
        .extracting(envelope -> envelope.event().executionId())
        .containsExactly("e1", "e1");
  }

  @Test
  void stream_withTree_alsoDeliversTheEventsOfTheRunsTheExecutionStarted() {
    when(tracker.isWithin(anyString(), anyString())).thenReturn(false);
    when(tracker.isWithin("e2", "e1")).thenReturn(true);

    final var events = collect(new ExecutionEventFeed(), "e1", true, null, null);

    assertThat(events)
        .extracting(envelope -> envelope.event().executionId())
        .containsExactly("e1", "e1", "e2", "e1");
  }

  @Test
  void stream_carriesTheRootAndDepthOfTheEventsRun() {
    when(tracker.lineage("e2")).thenReturn(new TaskTrackerService.Lineage("e1", 2));

    final var events = collect(new ExecutionEventFeed(), null, null, null);

    assertThat(events)
        .extracting(
            envelope -> envelope.event().executionId(),
            EventsController.Envelope::rootExecutionId,
            EventsController.Envelope::depth)
        .contains(tuple("e1", "e1", 0), tuple("e2", "e1", 2));
  }

  @Test
  void stream_withTree_leavesOutEventsOfNoRun() {
    final var feed = new ExecutionEventFeed();
    final var received =
        new EventsController(feed, tracker)
            .stream("e1", true, null, null)
                .filter(event -> event.data() != null)
                .map(ServerSentEvent::data)
                .take(Duration.ofMillis(500))
                .collectList()
                .toFuture();
    Flux.just(new ExecutionEvent.EventsMissed(3, Instant.now()), workflow("e1", "SUCCESS"))
        .delaySubscription(Duration.ofMillis(100))
        .publishOn(Schedulers.single())
        .subscribe(feed::publish);

    assertThat(received.join())
        .extracting(envelope -> envelope.event().executionId())
        .containsExactly("e1");
  }

  @Test
  void stream_withoutTree_leavesTheRunsTheExecutionStartedOut() {
    final var events = collect(new ExecutionEventFeed(), "e1", false, null, null);

    assertThat(events).extracting(envelope -> envelope.event().executionId()).containsOnly("e1");
    verify(tracker, never()).isWithin(anyString(), anyString());
  }
}
