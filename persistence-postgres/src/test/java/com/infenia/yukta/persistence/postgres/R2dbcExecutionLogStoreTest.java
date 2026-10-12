// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.log.ExecutionLogEvent;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.UseConcurrentHashMap",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert"
})
class R2dbcExecutionLogStoreTest extends PostgresTestSupport {

  private R2dbcExecutionLogStore store;

  @BeforeEach
  void setUp() {
    store = new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1_000, 5_000);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  private static ExecutionLogEvent started(final String run) {
    return ExecutionLogEvent.started(run, "s", "w", "v", Map.of("k", "v"));
  }

  private static ExecutionLogEvent finished(final String run) {
    return ExecutionLogEvent.finished(run, "s", "w", "SUCCESS", Map.of());
  }

  @Test
  void eventsComeBackInOrder_withTheirPayload() {
    store.append(started("r1"));
    store.append(finished("r1"));

    final List<ExecutionLogEvent> events = store.readAll();

    assertThat(events)
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
    assertThat(events.getFirst().payload()).isEqualTo(Map.of("k", "v"));
  }

  @Test
  void replaceAllSwapsTheWholeLog() {
    store.append(started("old"));

    store.replaceAll(List.of(started("new1"), started("new2")));

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("new1", "new2");
  }

  @Test
  void unreadableRow_isSkipped() {
    store.append(started("r1"));
    store.readAll();
    db.sql(
            "INSERT INTO execution_log (execution_id, event) VALUES ('bad',"
                + " CAST('{\"type\":\"NOPE\"}' AS jsonb))")
        .fetch()
        .rowsUpdated()
        .block();

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsExactly("r1");
  }

  @Test
  void unwritablePayload_isLeftOut_butTheEventIsKept() {
    final Map<String, Object> cyclic = new java.util.HashMap<>();
    cyclic.put("self", cyclic);
    store.append(ExecutionLogEvent.started("r1", "s", "w", "v", cyclic));

    final List<ExecutionLogEvent> events = store.readAll();

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().payload()).isNull();
  }

  @Test
  void trimKeepsTheNewestRunsAndEveryOpenRun() {
    final R2dbcExecutionLogStore trimming =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 8, 5_000);
    trimming.append(started("long"));
    for (final String run : List.of("r2", "r3", "r4")) {
      trimming.append(started(run));
      trimming.append(finished(run));
    }
    trimming.append(
        ExecutionLogEvent.control(true, ExecutionLogEvent.SCHEDULER, "SetLimits(1)", null));

    assertThat(trimming.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .contains("long", "r3", "r4", ExecutionLogEvent.SCHEDULER)
        .doesNotContain("r2");
    trimming.close();
  }

  @Test
  void schedulerChangesAreTrimmedToTheNewestFew() {
    final R2dbcExecutionLogStore trimming =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 4, 5_000);
    for (int i = 0; i < 4; i++) {
      trimming.append(
          ExecutionLogEvent.control(
              true, ExecutionLogEvent.SCHEDULER, "SetLimits(" + i + ")", null));
    }

    assertThat(trimming.readAll())
        .extracting(ExecutionLogEvent::status)
        .containsExactly("SetLimits(2)", "SetLimits(3)");
    trimming.close();
  }

  @Test
  void closeThatCannotWaitLongEnough_stillStopsTheWriter() {
    final R2dbcExecutionLogStore impatient =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1_000, 0);
    for (int i = 0; i < 30; i++) {
      impatient.append(started("r" + i));
    }

    impatient.close();

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(20))
        .until(
            () ->
                Long.valueOf(30)
                    .equals(
                        db.sql("SELECT count(*) AS n FROM execution_log")
                            .map(row -> row.get("n", Long.class))
                            .one()
                            .block()));
  }

  @Test
  void closeWhileInterrupted_keepsTheInterruptFlag() {
    for (int i = 0; i < 20; i++) {
      store.append(started("r" + i));
    }
    Thread.currentThread().interrupt();
    try {
      store.close();
    } finally {
      assertThat(Thread.interrupted()).isTrue();
    }
  }

  @Test
  void eventThatCannotBeWrittenAtAll_isLoggedNotThrown() {
    final ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("cannot write"));
    final R2dbcExecutionLogStore failing = new R2dbcExecutionLogStore(db, broken, 2, 1_000, 5_000);

    failing.append(ExecutionLogEvent.finished("r1", "s", "w", "SUCCESS", Map.of()));

    assertThat(failing.readAll()).isEmpty();
    failing.close();
  }

  @Test
  void afterClose_appendsAreDropped_andReadsAreEmpty() {
    store.append(started("r1"));
    store.close();

    store.append(started("r2"));

    assertThat(store.readAll()).isEmpty();
  }

  @Test
  void interruptedCaller_getsTheFallback() {
    Thread.currentThread().interrupt();
    try {
      assertThat(store.readAll()).isEmpty();
    } finally {
      assertThat(Thread.interrupted()).isTrue();
    }
  }

  @Test
  void unreachableLog_neverFailsTheCaller() {
    db.sql("ALTER TABLE execution_log RENAME TO execution_log_gone").fetch().rowsUpdated().block();
    final R2dbcExecutionLogStore trimming =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1, 5_000);
    try {
      trimming.append(started("r1"));
      assertThat(trimming.readAll()).isEmpty();
      trimming.replaceAll(List.of(started("r2")));
      trimming.close();
    } finally {
      db.sql("ALTER TABLE execution_log_gone RENAME TO execution_log")
          .fetch()
          .rowsUpdated()
          .block();
    }
  }
}
