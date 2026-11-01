// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class LogRetentionTest {

  private static final String NEWEST = "newest";

  private static ExecutionLogEvent change(final String status) {
    return ExecutionLogEvent.control(true, ExecutionLogEvent.SCHEDULER, status, null);
  }

  private static ExecutionLogEvent started(final String run, final String session) {
    return ExecutionLogEvent.started(run, session, "wf", "v1");
  }

  private static ExecutionLogEvent finished(final String run, final String session) {
    return ExecutionLogEvent.finished(run, session, "wf", "SUCCESS", Map.of());
  }

  @Test
  void trim_dropsOldRunsButKeepsTheNewestOnesAndTheirEvents() {
    final List<ExecutionLogEvent> events =
        List.of(
            started("r1", "s"),
            finished("r1", "s"),
            started("r2", "s"),
            finished("r2", "s"),
            started("r3", "s"));

    assertThat(LogRetention.trim(events, 2))
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("r2", "r2", "r3");
  }

  @Test
  void trim_keepsRunsThatNeverEndedEvenWhenTheyFellOutOfTheLimit() {
    final ExecutionLogEvent open = started("open", "s");
    final ExecutionLogEvent aborting = started("aborted", "s");
    final List<ExecutionLogEvent> events =
        List.of(
            open,
            started("done", "s"),
            finished("done", "s"),
            aborting,
            ExecutionLogEvent.aborted(aborting),
            started(NEWEST, "s"),
            ExecutionLogEvent.nodeFinished(NEWEST, "s", "wf", "node"));

    assertThat(LogRetention.trim(events, 1))
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("open", NEWEST, NEWEST);
  }

  @Test
  void trim_dropsEventsWithoutSessionUnlessTheirRunIsKept() {
    final List<ExecutionLogEvent> events =
        List.of(
            new ExecutionLogEvent(
                ExecutionLogEvent.Type.RUN_STARTED,
                "orphan",
                null,
                "wf",
                null,
                null,
                Map.of(),
                null,
                null,
                Instant.now(),
                null),
            new ExecutionLogEvent(
                ExecutionLogEvent.Type.RUN_FINISHED,
                "orphan",
                null,
                "wf",
                null,
                "SUCCESS",
                Map.of(),
                null,
                null,
                Instant.now(),
                null),
            started("b", "s"));

    assertThat(LogRetention.trim(events, 1))
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("b");
  }

  @Test
  void schedulerChanges_areTrimmedEvenWhenTheRunCountIsWithinTheLimit() {
    final List<ExecutionLogEvent> events =
        List.of(change("SetLimits(1)"), change("SetLimits(2)"), change("SetLimits(3)"));

    assertThat(LogRetention.trim(events, 2))
        .extracting(ExecutionLogEvent::status)
        .containsExactly("SetLimits(2)", "SetLimits(3)");
  }

  @Test
  void logWithinTheLimits_isReturnedAsIs() {
    final List<ExecutionLogEvent> events = List.of(change("SetLimits(1)"));

    assertThat(LogRetention.trim(events, 2)).isSameAs(events);
  }

  @Test
  void keepGivesEverySessionItsNewestRunsEvenWhenBusySessionFillsTheGlobalLimit() {
    final List<String> runs = new java.util.ArrayList<>();
    runs.add("quiet-1");
    runs.add("quiet-2");
    for (int i = 0; i < 50; i++) {
      runs.add("busy-" + i);
    }

    final java.util.Set<String> keep =
        LogRetention.keep(runs, id -> id.startsWith("busy") ? "busy" : "quiet", 20);

    assertThat(keep).contains("quiet-1", "quiet-2", "busy-49", "busy-30");
    assertThat(keep).doesNotContain("busy-29");
    // the global 20 newest (all busy) plus the quiet session's 2
    assertThat(keep).hasSize(22);
  }
}
