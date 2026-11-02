// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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
  ExecutionEvent.EventsMissed.class,
  ExecutionEvent.InputRequested.class,
  ExecutionEvent.InputEscalated.class,
  ExecutionEvent.InputAnswered.class,
  ExecutionEvent.InputExpired.class,
  ExecutionEvent.InputCancelled.class
})
public class EventsController {

  /** The kinds of event, the {@code type} of an envelope; the classes registered above. */
  public static final List<String> TYPES =
      Stream.of(
              ExecutionEvent.NodeStatusChanged.class,
              ExecutionEvent.WorkflowStatusChanged.class,
              ExecutionEvent.ControlOutcome.class,
              ExecutionEvent.LogLine.class,
              ExecutionEvent.EventsMissed.class,
              ExecutionEvent.InputRequested.class,
              ExecutionEvent.InputEscalated.class,
              ExecutionEvent.InputAnswered.class,
              ExecutionEvent.InputExpired.class,
              ExecutionEvent.InputCancelled.class)
          .map(Class::getSimpleName)
          .toList();

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
   * @param type only events of these kinds, a comma-separated list of {@link #TYPES} (case and
   *     blanks do not matter), such as {@code InputRequested,InputEscalated}; an unknown kind is
   *     refused with the valid ones
   * @param severity only events of this severity or above: INFO, WARN or ERROR
   * @return the events with a keep-alive comment every 15 seconds
   */
  @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream execution events",
      description =
          "Live node and workflow status changes, control command outcomes, log lines and input"
              + " request changes of all executions, each with a severity (INFO, WARN, ERROR)."
              + " type takes a comma-separated list of kinds; an unknown kind is a 400 that lists"
              + " the valid ones.")
  public Flux<ServerSentEvent<Envelope>> stream(
      @Parameter(description = "Only events of this execution") @RequestParam(required = false)
          final String executionId,
      @Parameter(
              description =
                  "With executionId, also the events of the sub-workflow runs it started, at any"
                      + " depth")
          @RequestParam(defaultValue = "false")
          final boolean tree,
      @Parameter(
              description =
                  "Only events of these kinds, comma-separated: NodeStatusChanged,"
                      + " WorkflowStatusChanged, ControlOutcome, LogLine, EventsMissed,"
                      + " InputRequested, InputEscalated, InputAnswered, InputExpired,"
                      + " InputCancelled")
          @RequestParam(required = false)
          final String type,
      @Parameter(description = "Only events of this severity or above: INFO, WARN or ERROR")
          @RequestParam(required = false)
          final String severity) {
    final EventSeverity minimum =
        severity == null ? EventSeverity.INFO : EventSeverity.parse(severity);
    final Set<String> kinds = kinds(type);
    return SseStreams.events(
        feed.events()
            .filter(
                event ->
                    executionId == null
                        || executionId.equals(event.executionId())
                        || tree && tracker.isWithin(event.executionId(), executionId))
            .map(this::envelope)
            .filter(envelope -> kinds.isEmpty() || kinds.contains(envelope.type()))
            .filter(envelope -> envelope.severity().compareTo(minimum) >= 0),
        null,
        SseStreams.HEARTBEAT);
  }

  /**
   * Reads the {@code type} filter.
   *
   * @param type a comma-separated list of kinds, or {@code null}
   * @return the kinds as {@link #TYPES} spells them; empty for no filter
   * @throws IllegalArgumentException naming the valid kinds, for a kind that is not one
   */
  private static Set<String> kinds(final String type) {
    final Set<String> kinds = new HashSet<>();
    if (type == null) {
      return kinds;
    }
    for (final String raw : type.split(",", -1)) {
      final String name = raw.strip();
      if (!name.isEmpty()) {
        kinds.add(
            TYPES.stream()
                .filter(name::equalsIgnoreCase)
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "Unknown event type: "
                                + name
                                + ". Valid types: "
                                + String.join(", ", TYPES))));
      }
    }
    return kinds;
  }
}
