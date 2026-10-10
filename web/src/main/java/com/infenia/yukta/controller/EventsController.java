// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
public class EventsController {

  /** The execution event feed. */
  private final ExecutionEventFeed feed;

  /** One event on the wire. */
  public record Envelope(String type, ExecutionEvent event) {}

  /**
   * Stream events of all executions as they happen.
   *
   * <p>Events are not replayed, and a slow reader misses events.
   *
   * @param executionId only events of this execution
   * @param type only events of this kind: NodeStatusChanged, WorkflowStatusChanged, ControlOutcome
   *     or LogLine
   * @return the events with a keep-alive comment every 15 seconds
   */
  @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream execution events",
      description =
          "Live node and workflow status changes, control command outcomes and log lines of all"
              + " executions")
  public Flux<ServerSentEvent<Envelope>> stream(
      @Parameter(description = "Only events of this execution") @RequestParam(required = false)
          final String executionId,
      @Parameter(description = "Only events of this kind") @RequestParam(required = false)
          final String type) {
    return SseStreams.events(
        feed.events()
            .filter(event -> executionId == null || executionId.equals(event.executionId()))
            .map(event -> new Envelope(event.getClass().getSimpleName(), event))
            .filter(envelope -> type == null || type.equalsIgnoreCase(envelope.type())),
        null,
        SseStreams.HEARTBEAT);
  }
}
