// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.UseConcurrentHashMap",
  "PMD.TooManyMethods",
  "PMD.UnitTestShouldIncludeAssert"
})
@ExpectedErrorLog({
  "ERROR com.infenia.yukta.persistence.postgres.Fencing - ",
  "ERROR com.infenia.yukta.persistence.postgres.R2dbcExecutionLogStore - "
})
class R2dbcExecutionLogStoreTest extends PostgresTestSupport {

  private final java.util.concurrent.CountDownLatch entered =
      new java.util.concurrent.CountDownLatch(1);

  private final java.util.concurrent.CountDownLatch release =
      new java.util.concurrent.CountDownLatch(1);

  private static final String OLD = "old";

  private static final String REASON = "reason";

  private static final String WRITE_FAILED = "write_failed";

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
    closeAndWait(store);
  }

  /** Stops the store and waits until it has flushed and closed. */
  private static void closeAndWait(final R2dbcExecutionLogStore target) {
    final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
    target.stop(closed::countDown);
    try {
      assertThat(closed.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
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

    final List<ExecutionLogEvent> events = store.readAll().collectList().block();

    assertThat(events)
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
    assertThat(events.getFirst().payload()).isEqualTo(Map.of("k", "v"));
  }

  @Test
  void manyEventsAreWrittenInBatches_andKeepTheirOrder() {
    closeAndWait(store);
    store = new R2dbcExecutionLogStore(db, new ObjectMapper(), 1_000, 100_000, 5_000);
    for (int i = 0; i < 250; i++) {
      store.append(started("r" + i));
    }

    assertThat(store.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactlyElementsOf(
            java.util.stream.IntStream.range(0, 250).mapToObj(i -> "r" + i).toList());
  }

  @Test
  void applyRecoveryDeletesOnlyTheDroppedRuns_andAppendsTheNewEvents() {
    store.append(started(OLD));
    store.append(started("kept"));

    store
        .applyRecovery(java.util.Set.of(OLD), 0, List.of(started("new1"), started("new2")))
        .block();

    assertThat(store.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("kept", "new1", "new2");
  }

  @Test
  void applyRecoveryThatFails_keepsTheOldLog() {
    store.append(started(OLD));
    store.readAll().collectList().block();
    // PostgreSQL text cannot hold a NUL character, so the second insert fails
    final ExecutionLogEvent unwritable = started(UNWRITABLE);

    store.applyRecovery(java.util.Set.of(OLD), 0, List.of(started("new1"), unwritable)).block();

    assertThat(store.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly(OLD);
  }

  @Test
  void appendThatCannotBeWritten_isSkippedAndTheLogStaysUsable() {
    store.append(started(UNWRITABLE));
    store.append(started("ok"));

    assertThat(store.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("ok");
  }

  @Test
  void applyRecoveryWritesMoreThanOneBatch() {
    final List<ExecutionLogEvent> events =
        java.util.stream.IntStream.range(0, 250).mapToObj(i -> started("r" + i)).toList();

    store.applyRecovery(java.util.Set.of(), 0, events).block();

    assertThat(store.readAll().collectList().block()).hasSize(250);
  }

  @Test
  void unreadableRow_isSkipped() {
    store.append(started("r1"));
    store.readAll().collectList().block();
    db.sql(
            "INSERT INTO execution_log (execution_id, type, ts, event) VALUES ('bad', 'NOPE',"
                + " now(), CAST('{\"type\":\"NOPE\"}' AS jsonb))")
        .fetch()
        .rowsUpdated()
        .block();

    assertThat(store.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("r1");
  }

  @Test
  void unwritablePayload_isLeftOut_butTheEventIsKept() {
    final Map<String, Object> cyclic = new java.util.HashMap<>();
    cyclic.put("self", cyclic);
    store.append(ExecutionLogEvent.started("r1", "s", "w", "v", cyclic));

    final List<ExecutionLogEvent> events = store.readAll().collectList().block();

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

    assertThat(trimming.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .contains("long", "r3", "r4", ExecutionLogEvent.SCHEDULER)
        .doesNotContain("r2");
    closeAndWait(trimming);
  }

  @Test
  void resumedRun_continuesUnderItsOwnId_andIsKeptWhileItRuns() {
    final R2dbcExecutionLogStore trimming =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 10, 5_000);
    final ExecutionLogEvent first = started("first");
    trimming.append(first);
    trimming.append(ExecutionLogEvent.aborted(first));
    trimming.append(ExecutionLogEvent.resumed(first, "again"));
    trimming.append(started("again"));
    for (final String run : List.of("r2", "r3", "r4")) {
      trimming.append(started(run));
      trimming.append(finished(run));
    }

    assertThat(trimming.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .contains("again", "r3", "r4")
        .doesNotContain("first", "r2");
    closeAndWait(trimming);
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

    assertThat(trimming.readAll().collectList().block())
        .extracting(ExecutionLogEvent::status)
        .containsExactly("SetLimits(2)", "SetLimits(3)");
    closeAndWait(trimming);
  }

  /** A store whose writer stays inside the first write until {@link #release} is counted down. */
  private R2dbcExecutionLogStore storeWithBusyWriter(final long shutdownWaitMillis) {
    final ObjectMapper real = new ObjectMapper();
    final ObjectMapper blocking = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(blocking.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
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

    closeAndWait(impatient);

    release.countDown();
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(20))
        .until(() ->
            Long.valueOf(1)
                .equals(
                    db.sql("SELECT count(*) AS n FROM execution_log")
                        .map(row -> row.get("n", Long.class))
                        .one()
                        .block()));
  }

  @Test
  void eventThatCannotBeWrittenAtAll_isLoggedNotThrown() {
    final ObjectMapper broken = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(broken.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("cannot write"));
    final R2dbcExecutionLogStore failing = new R2dbcExecutionLogStore(db, broken, 2, 1_000, 5_000);

    failing.append(ExecutionLogEvent.finished("r1", "s", "w", "SUCCESS", Map.of()));

    assertThat(failing.readAll().collectList().block()).isEmpty();
    closeAndWait(failing);
  }

  @Test
  void afterClose_appendsAreDropped_andReadsAreEmpty() {
    store.append(started("r1"));
    closeAndWait(store);

    store.append(started("r2"));

    assertThat(store.readAll().collectList().block()).isEmpty();
  }

  @Test
  void slowRead_doesNotHoldBackTheAppendsBehindIt() throws Exception {
    // parsing the "slow" run takes 3 s; the read used to hold the write pipeline that long
    final ObjectMapper real = new ObjectMapper();
    final ObjectMapper slow = org.mockito.Mockito.spy(real);
    org.mockito.Mockito.doAnswer(
            call -> {
              final String row = call.getArgument(0);
              if (row.contains("\"slow\"")) {
                Thread.sleep(3_000); // nosem: yukta-test-thread-sleep
              }
              return call.callRealMethod();
            })
        .when(slow)
        .readValue(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.<Class<ExecutionLogEvent>>any());
    final R2dbcExecutionLogStore reads = new R2dbcExecutionLogStore(db, slow, 2, 1_000, 5_000);
    reads.append(started("slow"));
    reads.forExecution("other").collectList().block(); // the start is written

    final var read = reads.forExecution("slow").collectList().toFuture();
    reads.append(started("fast"));

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofMillis(1_500))
        .until(() ->
            Long.valueOf(1L)
                .equals(
                    db.sql("SELECT count(*) AS n FROM execution_log WHERE execution_id = 'fast'")
                        .map(row -> row.get("n", Long.class))
                        .one()
                        .block()));
    assertThat(read.get(10, java.util.concurrent.TimeUnit.SECONDS))
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("slow");
    closeAndWait(reads);
  }

  @Test
  void cancelledRead_givesUpQuietly() {
    // a busy writer, so the read waits behind it on a boundedElastic thread
    final ObjectMapper slow = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(slow.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(_ -> {
          // simulated slow writer
          Thread.sleep(500); // nosem: yukta-test-thread-sleep
          return "{}";
        });
    final R2dbcExecutionLogStore busy = new R2dbcExecutionLogStore(db, slow, 2, 1_000, 5_000);
    busy.append(started("r1"));

    final var read = busy.readAll().subscribe();
    org.mockito.Mockito.verify(slow, org.mockito.Mockito.timeout(5_000))
        .writeValueAsString(org.mockito.ArgumentMatchers.any());
    // cancelling interrupts the waiting thread, which gives up with nothing
    read.dispose();
    assertThat(read.isDisposed()).isTrue();
    closeAndWait(busy);
  }

  @Test
  void failedTask_keepsThePipelineGoing_andNothingQueuesAfterStop() {
    assertThat(
            store.enqueue(
                reactor.core.publisher.Mono.error(new IllegalStateException("task failed"))))
        .isTrue();
    store.append(started("r1"));
    assertThat(store.readAll().collectList().block()).hasSize(1);

    closeAndWait(store);

    assertThat(store.enqueue(reactor.core.publisher.Mono.empty())).isFalse();
    assertThat(store.emit(reactor.core.publisher.Mono.empty())).isFalse();
  }

  @Test
  void closeThatCannotWait_countsTheRunEndsStillWaitingAsLost() throws InterruptedException {
    final ObjectMapper real = new ObjectMapper();
    final ObjectMapper blocking = org.mockito.Mockito.mock(ObjectMapper.class);
    org.mockito.Mockito.when(blocking.writeValueAsString(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          entered.countDown();
          release.await();
          return real.writeValueAsString(invocation.getArgument(0));
        });
    final java.util.concurrent.BlockingQueue<ExecutionLogEvent> full =
        new java.util.concurrent.ArrayBlockingQueue<>(1);
    full.add(started("queued"));
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore stuck =
        new R2dbcExecutionLogStore(db, blocking, 2, 1_000, 0, full, meters, 1);
    // the queue is full, so the run end waits in the second queue behind the blocked write
    stuck.append(finished("r1"));
    entered.await();

    closeAndWait(stuck);

    assertThat(meters.get(LOST).tag(REASON, WRITE_FAILED).counter().count()).isEqualTo(1.0);
    release.countDown();
  }

  @Test
  void stoppingTwice_runsEveryCallback_andTheStoreReportsItIsStopped() {
    assertThat(store.isRunning()).isTrue();
    store.start();
    closeAndWait(store);

    final java.util.concurrent.atomic.AtomicBoolean again =
        new java.util.concurrent.atomic.AtomicBoolean();
    store.stop(() -> again.set(true));
    store.stop();

    assertThat(again).isTrue();
    assertThat(store.isRunning()).isFalse();
    assertThat(store.getPhase()).isLessThan(0);
  }

  @Test
  void unreachableLog_neverFailsTheCaller() {
    db.sql("ALTER TABLE execution_log RENAME TO execution_log_gone").fetch().rowsUpdated().block();
    final R2dbcExecutionLogStore trimming =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1, 5_000);
    try {
      trimming.append(started("r1"));
      assertThat(trimming.readAll().collectList().block()).isEmpty();
      trimming.applyRecovery(java.util.Set.of("r1"), 0, List.of(started("r2"))).block();
      closeAndWait(trimming);
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
    closeAndWait(saturated);
  }

  @Test
  void queueGauge_countsTheEventsWaitingForTheWriter() {
    final java.util.concurrent.BlockingQueue<ExecutionLogEvent> waiting =
        new java.util.concurrent.ArrayBlockingQueue<>(4);
    waiting.add(started("waiting"));
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore gauged =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 2, 1_000, 5_000, waiting, meters, 1);

    assertThat(waiting).hasSize(1);
    assertThat(meters.get("yukta.execution.log.queue").gauge().value()).isEqualTo(1.0);
    closeAndWait(gauged);
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

    assertThat(saturated.readAll().collectList().block())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
    assertThat(meters.get(LOST).tag(REASON, "queue_full").counter().count()).isEqualTo(0.0);
    closeAndWait(saturated);
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

    assertThat(retrying.readAll().collectList().block())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_FINISHED);
    closeAndWait(retrying);
  }

  @Test
  void anEventThatCannotBeWritten_isCountedAsLost() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore counting =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 1_000, 100_000, 5_000, meters);

    counting.append(ExecutionLogEvent.nodeFinished(UNWRITABLE, "s", "w", "n"));
    counting.readAll().collectList().block();

    assertThat(meters.get(LOST).tag(REASON, WRITE_FAILED).counter().count()).isEqualTo(1.0);
    closeAndWait(counting);
  }

  @Test
  void runEndThatNeverGetsWritten_isCountedAsLostAfterTheRetries() throws InterruptedException {
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
    counting.readAll().collectList().block();
    closeAndWait(counting); // the event waited for a later try; shutdown ends the wait
    Thread.sleep(300); // the later try finds the writer stopped

    assertThat(meters.get(LOST).tag(REASON, WRITE_FAILED).counter().count()).isEqualTo(1.0);
  }

  @Test
  void runEndThatFailsAfterShutdown_isCountedAsLostAtOnce() {
    final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters =
        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    final R2dbcExecutionLogStore stopped =
        new R2dbcExecutionLogStore(db, new ObjectMapper(), 1_000, 100_000, 5_000, meters);
    closeAndWait(stopped);

    stopped.insert(finished(UNWRITABLE)).block();

    assertThat(meters.get(LOST).tag(REASON, WRITE_FAILED).counter().count()).isEqualTo(1.0);
  }

  @Test
  void runEndThatOutlastsTheRetries_isWrittenOnceTheDatabaseIsBack() throws InterruptedException {
    db.sql("ALTER TABLE execution_log RENAME TO execution_log_gone").fetch().rowsUpdated().block();
    final R2dbcExecutionLogStore waiting =
        new R2dbcExecutionLogStore(
            db,
            new ObjectMapper(),
            2,
            1_000,
            5_000,
            new java.util.concurrent.ArrayBlockingQueue<>(10),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            1);
    waiting.append(finished("r1"));
    waiting.readAll().collectList().block(); // the writer has used up the retries by now
    db.sql("ALTER TABLE execution_log_gone RENAME TO execution_log").fetch().rowsUpdated().block();
    Thread.sleep(500); // the later try runs after 128 ms

    assertThat(waiting.readAll().collectList().block())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_FINISHED);
    closeAndWait(waiting);
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
    assertThat(leader.readAll().collectList().block()).hasSize(1);

    leaseAt(2); // another server took over
    leader.append(started("r2"));
    leader.append(finished("r2"));
    leader.applyRecovery(java.util.Set.of("r1"), 0, List.of(started("r3"))).block();

    assertThat(leader.readAll().collectList().block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("r1");
    assertThat(meters.get("yukta.fencing.refused").tag("store", "execution_log").counter().count())
        .isEqualTo(3);
    closeAndWait(leader);
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
    assertThat(standby.readAll().collectList().block()).hasSize(1);
    closeAndWait(standby);
  }

  @Test
  void lookupsByRunAndByType_useTheIndexedQueries() {
    store.append(started("r1"));
    store.append(started("r2"));
    store.append(finished("r1"));

    assertThat(store.forExecution("r1").collectList().block())
        .extracting(ExecutionLogEvent::type)
        .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
    assertThat(store.forExecution("unknown").collectList().block()).isEmpty();
    assertThat(
            store
                .ofType(ExecutionLogEvent.Type.RUN_STARTED, java.time.Instant.EPOCH)
                .collectList()
                .block())
        .extracting(ExecutionLogEvent::executionId)
        .containsExactly("r1", "r2");
    assertThat(
            store
                .ofType(ExecutionLogEvent.Type.RUN_STARTED, java.time.Instant.now().plusSeconds(60))
                .collectList()
                .block())
        .isEmpty();
  }

  @Test
  void newest_isOneBoundedQueryNewestFirst() {
    store = new R2dbcExecutionLogStore(db, new ObjectMapper(), 1_000, 100_000, 5_000);
    store.append(started("r1"));
    store.append(started("r2"));
    store.append(finished("r1"));
    store.append(started("r3"));
    final var started = java.util.Set.of(ExecutionLogEvent.Type.RUN_STARTED);

    assertThat(
            store
                .newest(started, null, 2)
                .map(ExecutionLogEvent::executionId)
                .collectList()
                .block())
        .containsExactly("r3", "r2");
    assertThat(
            store
                .newest(started, "r1", 5)
                .map(ExecutionLogEvent::executionId)
                .collectList()
                .block())
        .containsExactly("r1");
    assertThat(
            store
                .newest(
                    java.util.Set.of(
                        ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED),
                    "r1",
                    5)
                .map(ExecutionLogEvent::type)
                .collectList()
                .block())
        .containsExactly(ExecutionLogEvent.Type.RUN_FINISHED, ExecutionLogEvent.Type.RUN_STARTED);
    assertThat(store.newest(java.util.Set.of(), null, 5).collectList().block()).isEmpty();
    assertThat(store.newest(started, null, 0).collectList().block()).isEmpty();
  }
}
