// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class ExecutionLogStoreTest {

  @TempDir /* default */ Path dir;

  private final JsonMapper mapper = JsonMapper.builder().build();

  private JsonlExecutionLogStore jsonl() {
    return new JsonlExecutionLogStore(dir.toString(), mapper);
  }

  @Test
  void withFsync_eventsAreWrittenAndReadBack() {
    final JsonlExecutionLogStore store =
        new JsonlExecutionLogStore(dir.toString(), mapper, 500, 10_000, 5_000L, true);
    store.append(ExecutionLogEvent.started("e1", "s1", "w1", "v1"));
    store.append(ExecutionLogEvent.finished("e1", "s1", "w1", "SUCCESS", Map.of()));

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
    store.close();
  }

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
                    started.timestamp())
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
  void jsonl_roundTripsEventsInOrder() {
    final JsonlExecutionLogStore store = jsonl();
    final ExecutionLogEvent started = ExecutionLogEvent.started("e1", "s1", "w1", "v1");
    final ExecutionLogEvent finished =
        ExecutionLogEvent.finished("e1", "s1", "w1", "FAILURE", Map.of("n1", "TIMED_OUT"));

    store.append(started);
    store.append(finished);
    store.close();

    assertThat(jsonl().readAll()).containsExactly(started, finished);
  }

  @Test
  void jsonl_missingFileReadsAsEmpty() {
    assertThat(jsonl().readAll()).isEmpty();
  }

  @Test
  void jsonl_skipsBlankAndTornLines() throws IOException {
    final JsonlExecutionLogStore store = jsonl();
    final ExecutionLogEvent started = ExecutionLogEvent.started("e1", "s1", "w1", "v1");
    store.append(started);
    store.readAll();
    Files.writeString(
        dir.resolve("executions.jsonl"),
        "\n{\"type\":\"RUN_FINI",
        StandardCharsets.UTF_8,
        java.nio.file.StandardOpenOption.APPEND);

    assertThat(store.readAll()).containsExactly(started);
  }

  @Test
  void jsonl_replaceAllRewritesTheFile_andLeavesNoTempFiles() throws IOException {
    final JsonlExecutionLogStore store = jsonl();
    store.append(ExecutionLogEvent.started("old", "s", "w", "v"));
    final ExecutionLogEvent kept = ExecutionLogEvent.started("new", "s", "w", "v");

    store.replaceAll(List.of(kept));

    assertThat(store.readAll()).containsExactly(kept);
    try (var files = Files.list(dir)) {
      assertThat(files.map(f -> f.toFile().getName()).toList()).containsExactly("executions.jsonl");
    }
  }

  @Test
  void jsonl_storageFailureNeverBreaksCaller() throws IOException {
    // the "directory" is a regular file, so nothing can be created beneath it
    final Path blocker = Files.createFile(dir.resolve("blocker"));
    final JsonlExecutionLogStore broken = new JsonlExecutionLogStore(blocker.toString(), mapper);

    broken.append(ExecutionLogEvent.started("e1", "s", "w", "v"));
    broken.replaceAll(List.of(ExecutionLogEvent.started("e2", "s", "w", "v")));

    assertThat(broken.readAll()).isEmpty();
  }

  @Test
  void jsonl_unreadablePathReadsAsEmpty() throws IOException {
    // executions.jsonl is a directory, so it exists but cannot be read as a file
    Files.createDirectory(dir.resolve("executions.jsonl"));

    assertThat(jsonl().readAll()).isEmpty();
  }

  @Test
  void jsonl_compactsToTheNewestRuns() {
    final JsonlExecutionLogStore store = new JsonlExecutionLogStore(dir.toString(), mapper, 2, 4);
    for (final String run : List.of("r1", "r2", "r3", "r4")) {
      store.append(ExecutionLogEvent.started(run, "s", "w", "v"));
      store.append(ExecutionLogEvent.finished(run, "s", "w", "SUCCESS", Map.of()));
    }

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsOnly("r3", "r4");
  }

  @Test
  void jsonl_compaction_keepsRunThatIsStillOpen() {
    final JsonlExecutionLogStore store = new JsonlExecutionLogStore(dir.toString(), mapper, 2, 8);
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
  void jsonl_compaction_ranksRunsByTheirLastEvent() {
    final JsonlExecutionLogStore store = new JsonlExecutionLogStore(dir.toString(), mapper, 2, 6);
    store.append(ExecutionLogEvent.started("long", "s", "w", "v"));
    store.append(ExecutionLogEvent.started("a", "s", "w", "v"));
    store.append(ExecutionLogEvent.finished("a", "s", "w", "SUCCESS", Map.of()));
    store.append(ExecutionLogEvent.started("b", "s", "w", "v"));
    store.append(ExecutionLogEvent.finished("b", "s", "w", "SUCCESS", Map.of()));
    store.append(ExecutionLogEvent.finished("long", "s", "w", "SUCCESS", Map.of()));

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .containsOnly("b", "long");
  }

  @Test
  void jsonl_doesNotCompactWhileWithinTheRetainedRuns() {
    final JsonlExecutionLogStore store = new JsonlExecutionLogStore(dir.toString(), mapper, 5, 2);
    store.append(ExecutionLogEvent.started("r1", "s", "w", "v"));
    store.append(ExecutionLogEvent.started("r2", "s", "w", "v"));

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).contains("r1", "r2");
  }

  @Test
  void jsonl_afterClose_dropsAppendsAndReadsAsEmpty() {
    final JsonlExecutionLogStore store = jsonl();
    store.append(ExecutionLogEvent.started("e1", "s", "w", "v"));
    store.close();

    store.append(ExecutionLogEvent.started("e2", "s", "w", "v"));

    assertThat(store.readAll()).isEmpty();
    assertThat(jsonl().readAll()).extracting(ExecutionLogEvent::executionId).containsExactly("e1");
  }

  @Test
  void jsonl_interruptedCallers_getTheFallback() {
    // a busy writer, so neither call can finish before the interrupt is noticed
    final ObjectMapper slow = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(slow.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            _ -> {
              Thread.sleep(500);
              return "{}";
            });
    final JsonlExecutionLogStore store =
        new JsonlExecutionLogStore(dir.toString(), slow, 5, 100, 5_000);
    store.append(ExecutionLogEvent.started("e1", "s", "w", "v"));
    Thread.currentThread().interrupt();
    try {
      assertThat(store.readAll()).isEmpty();
      assertThat(Thread.interrupted()).isTrue();
      Thread.currentThread().interrupt();
      store.close();
      assertThat(Thread.interrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void jsonl_closeGivesUpOnSlowWriter() {
    final ObjectMapper slow = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(slow.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            _ -> {
              Thread.sleep(300);
              return "{}";
            });
    final JsonlExecutionLogStore store =
        new JsonlExecutionLogStore(dir.toString(), slow, 5, 100, 20);
    store.append(ExecutionLogEvent.started("e1", "s", "w", "v"));

    store.close();

    assertThat(store.readAll()).isEmpty();
  }

  @Test
  void jsonl_keepsThePayloadAndNodeEvents() {
    final JsonlExecutionLogStore store = jsonl();
    final ExecutionLogEvent started =
        ExecutionLogEvent.started("e1", "s", "w", "v", Map.of("k", "v", "n", 1));
    final ExecutionLogEvent node = ExecutionLogEvent.nodeFinished("e1", "s", "w", "n1");

    store.append(started);
    store.append(node);

    assertThat(store.readAll()).containsExactly(started, node);
    assertThat(store.readAll().getFirst().payload()).containsEntry("k", "v");
  }

  @Test
  void jsonl_payloadThatCannotBeWritten_losesOnlyThePayload() {
    final JsonlExecutionLogStore store = jsonl();
    // a map that contains itself cannot be written as JSON
    final Map<String, Object> payload = new java.util.concurrent.ConcurrentHashMap<>();
    payload.put("self", payload);

    store.append(ExecutionLogEvent.started("e1", "s", "w", "v", payload));

    assertThat(store.readAll())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.executionId()).isEqualTo("e1");
              assertThat(e.payload()).isNull();
            });
  }

  @Test
  void jsonl_compaction_keepsRecentSchedulerChangesButNotOldOnes() {
    final JsonlExecutionLogStore store = new JsonlExecutionLogStore(dir.toString(), mapper, 2, 9);
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
  void jsonl_eventThatFailsWithoutPayload_isLoggedNotThrown() {
    final ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("cannot write"));
    final JsonlExecutionLogStore store =
        new JsonlExecutionLogStore(dir.toString(), broken, 5, 100, 1000);

    store.append(ExecutionLogEvent.started("e1", "s", "w", "v"));
    store.close();

    assertThat(store.readAll()).isEmpty();
  }
}
