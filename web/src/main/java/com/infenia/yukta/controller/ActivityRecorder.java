// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.activity.ActivityEntry;
import com.infenia.yukta.service.activity.ActivityStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the activity feed in the {@link ActivityStore}, so the Activity page can show what happened
 * before it was opened. Every event of the live stream is kept except log lines (the log store
 * holds them). Written in batches; the feed is best effort, so an event a slow reader missed is
 * missing here too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityRecorder {

  private static final int BATCH = 100;
  private static final Duration BATCH_WAIT = Duration.ofSeconds(1);

  private final ExecutionEventFeed feed;
  private final TaskTrackerService tracker;
  private final ActivityStore store;
  private final ObjectMapper mapper;

  private Disposable subscription;

  /** Starts recording. */
  /* default */ @PostConstruct
  void start() {
    subscription =
        feed.events()
            .filter(e -> !(e instanceof ExecutionEvent.LogLine))
            .map(this::entry)
            .bufferTimeout(BATCH, BATCH_WAIT)
            .concatMap(batch ->
                store
                    .append(batch)
                    .onErrorResume(e -> {
                      log.atWarn().setCause(e).log("The activity history could not be written");
                      return reactor.core.publisher.Mono.empty();
                    }))
            .subscribe();
  }

  /** Stops recording. */
  /* default */ @PreDestroy
  void stop() {
    if (subscription != null) {
      subscription.dispose();
    }
  }

  private ActivityEntry entry(final ExecutionEvent event) {
    final TaskTrackerService.Lineage lineage = tracker.lineage(event.executionId());
    final EventSeverity severity = EventSeverity.of(event);
    final EventsController.Envelope envelope =
        new EventsController.Envelope(
            event.getClass().getSimpleName(),
            severity,
            lineage.rootExecutionId(),
            lineage.depth(),
            event);
    return new ActivityEntry(
        0,
        event.timestamp(),
        envelope.type(),
        severity.name(),
        event.executionId(),
        mapper.writeValueAsString(envelope));
  }
}
