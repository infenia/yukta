// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/** Live feed of what happens to all executions, for monitoring and external supervisors. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Events API", description = "Live feed of execution events")
// Jackson serializes the sealed ExecutionEvent subtypes, which AOT cannot see from Envelope.
@RegisterReflectionForBinding({
  ExecutionEvent.NodeStatusChanged.class,
  ExecutionEvent.WorkflowStatusChanged.class,
  ExecutionEvent.ControlOutcome.class,
  ExecutionEvent.LogLine.class,
  ExecutionEvent.EventsMissed.class
})
public class EventsController {

  /** The execution event feed. */
  private final ExecutionEventFeed feed;

  /** Knows which runs a run started, for {@code tree}. */
  private final TaskTrackerService tracker;

  /**
   * One event on the wire.
   *
   * @param type the event kind
   * @param severity how serious it is
   * @param rootExecutionId the run at the top of the event's tree of runs (the run itself when it
   *     has no parent)
   * @param depth how many sub-workflow levels the event's run lies below the root
   * @param event the event
   */
  public record Envelope(
      String type,
      EventSeverity severity,
      String rootExecutionId,
      int depth,
      ExecutionEvent event) {}

  private Envelope envelope(final ExecutionEvent event) {
    final TaskTrackerService.Lineage lineage = tracker.lineage(event.executionId());
    return new Envelope(
        event.getClass().getSimpleName(),
        EventSeverity.of(event),
        lineage.rootExecutionId(),
        lineage.depth(),
        event);
  }

  /**
   * Stream events of all executions as they happen.
   *
   * <p>Events are not replayed, and a slow reader misses events.
   *
   * @param executionId only events of this execution
   * @param tree with executionId, also the events of every run it started as a sub-workflow, at any
   *     depth
   * @param type only events of this kind: NodeStatusChanged, WorkflowStatusChanged, ControlOutcome
   *     or LogLine
   * @param severity only events of this severity or above: INFO, WARN or ERROR
   * @return the events with a keep-alive comment every 15 seconds
   */
  @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream execution events",
      description =
          "Live node and workflow status changes, control command outcomes and log lines of all"
              + " executions, each with a severity (INFO, WARN, ERROR)")
  public Flux<ServerSentEvent<Envelope>> stream(
      @Parameter(description = "Only events of this execution") @RequestParam(required = false)
          final String executionId,
      @Parameter(
              description =
                  "With executionId, also the events of the sub-workflow runs it started, at any"
                      + " depth")
          @RequestParam(defaultValue = "false")
          final boolean tree,
      @Parameter(description = "Only events of this kind") @RequestParam(required = false)
          final String type,
      @Parameter(description = "Only events of this severity or above: INFO, WARN or ERROR")
          @RequestParam(required = false)
          final String severity) {
    final EventSeverity minimum =
        severity == null ? EventSeverity.INFO : EventSeverity.parse(severity);
    return SseStreams.events(
        feed.events()
            .filter(
                event ->
                    executionId == null
                        || executionId.equals(event.executionId())
                        || tree && tracker.isWithin(event.executionId(), executionId))
            .map(this::envelope)
            .filter(envelope -> type == null || type.equalsIgnoreCase(envelope.type()))
            .filter(envelope -> envelope.severity().compareTo(minimum) >= 0),
        null,
        SseStreams.HEARTBEAT);
  }
}
