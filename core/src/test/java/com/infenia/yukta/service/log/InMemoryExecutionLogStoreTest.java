// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class InMemoryExecutionLogStoreTest {

  @Test
  void eventFactories_fillTheRightFields() {
    final ExecutionLogEvent started = ExecutionLogEvent.started("e1", "s1", "w1", "v1");
    final ExecutionLogEvent finished =
        ExecutionLogEvent.finished("e1", "s1", "w1", "SUCCESS", Map.of("n1", "SUCCESS"));
    final ExecutionLogEvent aborted = ExecutionLogEvent.aborted(started);

    assertThat(started.type()).isEqualTo(ExecutionLogEvent.Type.RUN_STARTED);
    assertThat(started.definitionVersion()).isEqualTo("v1");
    assertThat(finished.status()).isEqualTo("SUCCESS");
    assertThat(finished.nodeStatuses()).containsEntry("n1", "SUCCESS");
    assertThat(aborted.type()).isEqualTo(ExecutionLogEvent.Type.RUN_ABORTED);
    assertThat(aborted.status()).isEqualTo("ABORTED");
    assertThat(aborted.executionId()).isEqualTo("e1");
    assertThat(
            new ExecutionLogEvent(
                    started.type(),
                    "e",
                    "s",
                    "w",
                    null,
                    null,
                    null,
                    null,
                    null,
                    started.timestamp(),
                    null)
                .nodeStatuses())
        .isEmpty();
  }

  @Test
  void inMemory_appendsAndReplaces() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore();
    final ExecutionLogEvent first = ExecutionLogEvent.started("a", "s", "w", "v");
    final ExecutionLogEvent second = ExecutionLogEvent.started("b", "s", "w", "v");

    store.append(first);
    store.append(second);
    assertThat(store.readAll()).containsExactly(first, second);

    store.replaceAll(List.of(second));
    assertThat(store.readAll()).containsExactly(second);
  }

  @Test
  void compactsToTheNewestRuns() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore(2, 4);
    for (final String run : List.of("r1", "r2", "r3", "r4")) {
      store.append(ExecutionLogEvent.started(run, "s", "w", "v"));
      store.append(ExecutionLogEvent.finished(run, "s", "w", "SUCCESS", Map.of()));
    }

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsOnly("r3", "r4");
  }

  @Test
  void compaction_keepsRunThatIsStillOpen_andDropsEndedOnes() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore(2, 8);
    store.append(ExecutionLogEvent.started("long", "s", "w", "v"));
    for (final String run : List.of("r2", "r3", "r4")) {
      store.append(ExecutionLogEvent.started(run, "s", "w", "v"));
      store.append(ExecutionLogEvent.finished(run, "s", "w", "SUCCESS", Map.of()));
    }
    store.append(ExecutionLogEvent.started("r5", "s", "w", "v"));

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .contains("long", "r4", "r5")
        .doesNotContain("r2");
  }

  @Test
  void compaction_treatsAbortedRunsAsEnded_andRanksRunsByTheirLastEvent() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore(1, 7);
    final ExecutionLogEvent lost = ExecutionLogEvent.started("lost", "s", "w", "v");
    store.append(lost);
    store.append(ExecutionLogEvent.nodeFinished("lost", "s", "w", "n"));
    store.append(ExecutionLogEvent.aborted(lost));
    store.append(ExecutionLogEvent.started("a", "s", "w", "v"));
    store.append(ExecutionLogEvent.finished("a", "s", "w", "SUCCESS", Map.of()));
    store.append(ExecutionLogEvent.started("b", "s", "w", "v"));
    store.append(ExecutionLogEvent.finished("b", "s", "w", "SUCCESS", Map.of()));

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsOnly("b");
  }

  @Test
  void doesNotCompactWhileWithinTheRetainedRuns() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore(5, 2);
    store.append(ExecutionLogEvent.started("r1", "s", "w", "v"));
    store.append(ExecutionLogEvent.started("r2", "s", "w", "v"));

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).contains("r1", "r2");
  }

  @Test
  void compaction_keepsRecentSchedulerChangesButNotOldOnes() {
    final InMemoryExecutionLogStore store = new InMemoryExecutionLogStore(2, 9);
    for (int i = 0; i < 3; i++) {
      store.append(
          ExecutionLogEvent.control(
              true, ExecutionLogEvent.SCHEDULER, "SetLimits(" + i + ")", null));
    }
    for (final String run : List.of("r1", "r2", "r3")) {
      store.append(ExecutionLogEvent.started(run, "s", "w", "v"));
      store.append(ExecutionLogEvent.finished(run, "s", "w", "SUCCESS", Map.of()));
    }

    assertThat(store.readAll())
        .filteredOn(e -> ExecutionLogEvent.SCHEDULER.equals(e.executionId()))
        .extracting(ExecutionLogEvent::status)
        .containsExactly("SetLimits(1)", "SetLimits(2)");
    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).doesNotContain("r1");
  }

  @Test
  void otherEventFactories_fillTheirFields() {
    final ExecutionLogEvent started =
        ExecutionLogEvent.started("e", "s", "w", "v", Map.of("k", "v"));

    assertThat(ExecutionLogEvent.idempotencyKey("e", "key").status()).isEqualTo("key");
    final ExecutionLogEvent resumed = ExecutionLogEvent.resumed(started, "e2");
    assertThat(resumed.type()).isEqualTo(ExecutionLogEvent.Type.RUN_RESUMED);
    assertThat(resumed.status()).isEqualTo("e2");
    assertThat(ExecutionLogEvent.aborted(started, Map.of("n", "FAILURE")).nodeStatuses())
        .containsEntry("n", "FAILURE");
    assertThat(ExecutionLogEvent.control(true, "e", "PAUSE", null).status()).isEqualTo("PAUSE");
    assertThat(ExecutionLogEvent.control(false, "e", "PAUSE", "no").status())
        .isEqualTo("PAUSE: no");
    assertThat(started.payload()).containsEntry("k", "v");
    assertThat(started.withoutPayload().payload()).isNull();
  }
}
