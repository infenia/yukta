// SPDX-License-Identifier: AGPL-3.0-only
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
  "PMD.AvoidAccessibilityAlteration",
  "PMD.UnitTestShouldIncludeAssert"
})
class R2dbcExecutionLogStoreTest extends PostgresTestSupport {

  private final java.util.concurrent.CountDownLatch entered =
      new java.util.concurrent.CountDownLatch(1);

  private final java.util.concurrent.CountDownLatch release =
      new java.util.concurrent.CountDownLatch(1);

  private static final String REASON = "reason";

  private static final String LOST = "yukta.execution.log.lost";

  /** An id PostgreSQL cannot store. */
  private static final String UNWRITABLE = "a\u0000b";

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
  void manyEventsAreWrittenInBatches_andKeepTheirOrder() {
    store.close();
    store = new R2dbcExecutionLogStore(db, new ObjectMapper(), 1_000, 100_000, 5_000);
    for (int i = 0; i < 250; i++) {
      store.append(started("r" + i));
    }

    assertThat(store.readAll())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactlyElementsOf(
            java.util.stream.IntStream.range(0, 250).mapToObj(i -> "r" + i).toList());
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
  void replaceAllThatFails_keepsTheOldLog() {
    store.append(started("old"));
    store.readAll();
    // PostgreSQL text cannot hold a NUL character, so the second insert fails
    final ExecutionLogEvent unwritable = started(UNWRITABLE);

    store.replaceAll(List.of(started("new1"), unwritable));

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsExactly("old");
  }

  @Test
  void appendThatCannotBeWritten_isSkippedAndTheLogStaysUsable() {
    store.append(started(UNWRITABLE));
    store.append(started("ok"));

    assertThat(store.readAll()).extracting(ExecutionLogEvent::executionId).containsExactly("ok");
  }

  @Test
  void replaceAllWritesMoreThanOneBatch() {
    final List<ExecutionLogEvent> events =
        java.util.stream.IntStream.range(0, 250).mapToObj(i -> started("r" + i)).toList();

    store.replaceAll(events);

    assertThat(store.readAll()).hasSize(250);
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

  /** A store whose writer stays inside the first write until {@link #release} is counted down. */
  private R2dbcExecutionLogStore storeWithBusyWriter(final long shutdownWaitMillis) {
    final ObjectMapper real = new ObjectMapper();
    final ObjectMapper blocking = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(blocking.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return real.writeValueAsString(invocation.getArgument(0));
            });
    return new R2dbcExecutionLogStore(db, blocking, 2, 1_000, shutdownWaitMillis);
  }

  @Test
  void closeThatCannotWaitLongEnough_stillStopsTheWriter() throws InterruptedException {
    final R2dbcExecutionLogStore impatient = storeWithBusyWriter(0);
    impatient.append(started("r1"));
    entered.await();

    impatient.close();

    release.countDown();
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(20))
        .until(
            () ->
                Long.valueOf(1)
                    .equals(
                        db.sql("SELECT count(*) AS n FROM execution_log")
                            .map(row -> row.get("n", Long.class))
                            .one()
                            .block()));
  }

  @Test
  void closeWhileInterrupted_keepsTheInterruptFlag() throws InterruptedException {
    final R2dbcExecutionLogStore busy = storeWithBusyWriter(5_000);
    busy.append(started("r1"));
    entered.await();
    Thread.currentThread().interrupt();
    try {
      busy.close();
    } finally {
      assertThat(Thread.interrupted()).isTrue();
      release.countDown();
    }
    busy.close();
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

  @Test
  void whenTheQueueIsFull_theEventIsDropped() {
    final java.util.concurrent.BlockingQueue<ExecutionLogEvent> full =
        new java.util.concurrent.ArrayBlockingQueue<>(1);
    full.add(started("waiting"));
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore saturated =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1_000, 5_000, full, meters, 1);

    saturated.append(ExecutionLogEvent.nodeFinished("dropped", "s", "w", "n"));

    assertThat(full).hasSize(1);
    assertThat(meters.get(LOST).tag(REASON, "queue_full").counter().count()).isEqualTo(1.0);
    saturated.close();
  }

  @Test
  void whenTheQueueIsFull_theRunEndIsStillWritten() {
    final java.util.concurrent.BlockingQueue<ExecutionLogEvent> full =
        new java.util.concurrent.ArrayBlockingQueue<>(1);
    full.add(started("waiting"));
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore saturated =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1_000, 5_000, full, meters, 1);

    saturated.append(finished("r1"));

    assertThat(saturated.readAll())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
    assertThat(meters.get(LOST).tag(REASON, "queue_full").counter().count()).isEqualTo(0.0);
    saturated.close();
  }

  @Test
  void runEndThatFailsToInsert_isRetriedUntilTheDatabaseIsBack() {
    db.sql("ALTER TABLE execution_log RENAME TO execution_log_gone").fetch().rowsUpdated().block();
    final R2dbcExecutionLogStore retrying =
        new R2dbcExecutionLogStore(
            db,
            new ObjectMapper(),
            2,
            1_000,
            5_000,
            new java.util.concurrent.ArrayBlockingQueue<>(10),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            400);
    retrying.append(finished("r1"));
    db.sql("ALTER TABLE execution_log_gone RENAME TO execution_log").fetch().rowsUpdated().block();

    assertThat(retrying.readAll())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_FINISHED);
    retrying.close();
  }

  @Test
  void anEventThatCannotBeWritten_isCountedAsLost() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore counting =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 1_000, 100_000, 5_000, meters);

    counting.append(ExecutionLogEvent.nodeFinished(UNWRITABLE, "s", "w", "n"));
    counting.readAll();

    assertThat(meters.get(LOST).tag(REASON, "write_failed").counter().count()).isEqualTo(1.0);
    counting.close();
  }

  @Test
  void runEndThatNeverGetsWritten_isCountedAsLostAfterTheRetries() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore counting =
        new R2dbcExecutionLogStore(
            db,
            new ObjectMapper(),
            2,
            1_000,
            5_000,
            new java.util.concurrent.ArrayBlockingQueue<>(10),
            meters,
            1);

    counting.append(finished(UNWRITABLE));
    counting.readAll();

    assertThat(meters.get(LOST).tag(REASON, "write_failed").counter().count()).isEqualTo(1.0);
    counting.close();
  }

  @Test
  void retryOfRunEnd_stopsWhenTheWriterIsInterrupted() throws ReflectiveOperationException {
    final java.lang.reflect.Method insert =
        R2dbcExecutionLogStore.class.getDeclaredMethod("insert", ExecutionLogEvent.class);
    insert.setAccessible(true);
    Thread.currentThread().interrupt();
    try {
      insert.invoke(store, finished(UNWRITABLE));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static void leaseAt(final long fencing) {
    db.sql("DELETE FROM leader_lease").fetch().rowsUpdated().block();
    db.sql(
            "INSERT INTO leader_lease (name, holder, fencing, expires_at)"
                + " VALUES ('leader', 'other', :f, now() + interval '1 hour')")
        .bind("f", fencing)
        .fetch()
        .rowsUpdated()
        .block();
  }

  @Test
  void staleLeader_appendsAreRefusedAndCounted_currentLeaderStillWrites() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    leaseAt(1);
    final R2dbcExecutionLogStore leader =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1_000, 5_000, meters, () -> 1);
    leader.append(started("r1"));
    assertThat(leader.readAll()).hasSize(1);

    leaseAt(2); // another server took over
    leader.append(started("r2"));
    leader.append(finished("r2"));
    leader.replaceAll(List.of(started("r3")));

    assertThat(leader.readAll()).extracting(ExecutionLogEvent::executionId).containsExactly("r1");
    assertThat(meters.get("yukta.fencing.refused").tag("store", "execution_log").counter().count())
        .isEqualTo(3);
    leader.close();
  }

  @Test
  void leaderWithoutToken_isNotFenced() {
    leaseAt(5);
    final R2dbcExecutionLogStore standby =
        new R2dbcExecutionLogStore(
            db,
            new ObjectMapper(),
            2,
            1_000,
            5_000,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            () -> 0);
    standby.append(started("r1"));
    assertThat(standby.readAll()).hasSize(1);
    standby.close();
  }
}
