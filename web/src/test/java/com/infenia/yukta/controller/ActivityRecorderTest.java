// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.activity.ActivityEntry;
import com.infenia.yukta.service.activity.ActivityStore;
import com.infenia.yukta.service.activity.InMemoryActivityStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Tests for {@link ActivityRecorder}. */
@NoArgsConstructor
class ActivityRecorderTest {

  private static final Instant T = Instant.parse("2026-11-28T10:00:00Z");

  private static List<ActivityEntry> waitForEntries(final ActivityStore store, final int count) {
    List<ActivityEntry> found = List.of();
    for (int i = 0; i < 60 && found.size() < count; i++) {
      Mono.delay(Duration.ofMillis(100)).block();
      found = store.newest(Set.of(), Set.of(), null, 10).collectList().block();
    }
    return found;
  }

  private static TaskTrackerService tracker() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class);
    when(tracker.lineage("e1")).thenReturn(new TaskTrackerService.Lineage("root", 2));
    return tracker;
  }

  @Test
  void keepsEveryEventButLogLinesWithTheEnvelopeOfTheStream() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final InMemoryActivityStore store = new InMemoryActivityStore();
    final ActivityRecorder recorder =
        new ActivityRecorder(feed, tracker(), store, JsonMapper.builder().build());
    recorder.start();
    try {
      feed.publish(
          new ExecutionEvent.LogLine(
              "e1", "s1", "w1", "n1", "hello", com.infenia.yukta.plugin.core.LogLevel.INFO, T));
      feed.publish(new ExecutionEvent.WorkflowStatusChanged("e1", "s1", "w1", "FAILURE", T));

      final List<ActivityEntry> found = waitForEntries(store, 1);

      assertThat(found).hasSize(1);
      final ActivityEntry entry = found.getFirst();
      assertThat(entry.type()).isEqualTo("WorkflowStatusChanged");
      assertThat(entry.severity()).isEqualTo("ERROR");
      assertThat(entry.executionId()).isEqualTo("e1");
      assertThat(entry.at()).isEqualTo(T);
      assertThat(entry.json())
          .contains("\"rootExecutionId\":\"root\"")
          .contains("\"depth\":2")
          .contains("\"status\":\"FAILURE\"");
    } finally {
      recorder.stop();
    }
  }

  @Test
  void failingStoreIsLoggedAndRecordingGoesOn() {
    final ExecutionEventFeed feed = new ExecutionEventFeed();
    final ActivityStore failing = mock(ActivityStore.class);
    when(failing.append(org.mockito.ArgumentMatchers.anyList()))
        .thenReturn(Mono.error(new IllegalStateException("down")))
        .thenReturn(Mono.empty());
    final ActivityRecorder recorder =
        new ActivityRecorder(feed, tracker(), failing, JsonMapper.builder().build());
    recorder.start();
    try {
      feed.publish(new ExecutionEvent.WorkflowStatusChanged("e1", "s1", "w1", "RUNNING", T));
      Mono.delay(Duration.ofMillis(1500)).block();
      feed.publish(new ExecutionEvent.WorkflowStatusChanged("e1", "s1", "w1", "SUCCESS", T));
      Mono.delay(Duration.ofMillis(1500)).block();

      org.mockito.Mockito.verify(failing, org.mockito.Mockito.times(2))
          .append(org.mockito.ArgumentMatchers.anyList());
    } finally {
      recorder.stop();
    }
  }

  @Test
  void stopBeforeStartIsHarmless() {
    final ActivityRecorder recorder =
        new ActivityRecorder(
            new ExecutionEventFeed(),
            tracker(),
            new InMemoryActivityStore(),
            JsonMapper.builder().build());
    assertThatCode(recorder::stop).doesNotThrowAnyException();
  }
}
