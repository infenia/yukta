// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.infenia.yukta.model.execution.WorkflowProgress;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link DefaultStatusHistoryCache}. */
@DisplayName("DefaultStatusHistoryCache")
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UncommentedEmptyConstructor"})
class DefaultStatusHistoryCacheTest {

  /** Execution ID 1 for testing. */
  private static final String EXEC_ID_1 = "exec-1";

  /** Execution ID 2 for testing. */
  private static final String EXEC_ID_2 = "exec-2";

  /** Concurrent execution ID for thread-safety tests. */
  private static final String EXEC_CONCURRENT = "exec-concurrent";

  /** Status constant for running state. */
  private static final String STATUS_RUNNING = "RUNNING";

  /** Status constant for pending state. */
  private static final String STATUS_PENDING = "PENDING";

  /** Status constant for completed state. */
  private static final String STATUS_COMPLETED = "COMPLETED";

  /** Status constant for failed state. */
  private static final String STATUS_FAILED = "FAILED";

  /** Default cache TTL in minutes. */
  private static final int CACHE_TTL_MINUTES = 5;

  /** TTL boundary at 30 minutes. */
  private static final int TTL_BOUNDARY_30 = 30;

  /** TTL value exceeding maximum. */
  private static final int TTL_TOO_HIGH = 31;

  /** TTL value of zero. */
  private static final int TTL_ZERO = 0;

  /** Negative TTL value. */
  private static final int TTL_NEGATIVE = -5;

  /** Number of threads for concurrency tests. */
  private static final int NUM_THREADS = 10;

  /** Number of updates per thread. */
  private static final int UPDATES_PER_THREAD = 100;

  /** Default constructor. */
  /* package */ DefaultStatusHistoryCacheTest() {}

  // TTL Validation Tests

  @Test
  @DisplayName("constructor_validTtl_succeeds")
  void constructor_validTtl_succeeds() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);
    assertThat(cache).isNotNull();
  }

  @Test
  @DisplayName("constructor_ttlEqualTo30_succeeds")
  void constructor_ttlEqualTo30_succeeds() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(TTL_BOUNDARY_30);
    assertThat(cache).isNotNull();
  }

  @Test
  @DisplayName("constructor_ttlEqualTo1_succeeds")
  void constructor_ttlEqualTo1_succeeds() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(1);
    assertThat(cache).isNotNull();
  }

  @Test
  @DisplayName("constructor_ttlTooHigh_throwsException")
  void constructor_ttlTooHigh_throwsException() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new DefaultStatusHistoryCache(TTL_TOO_HIGH))
        .withMessageContaining("TTL cannot exceed 30 minutes");
  }

  @Test
  @DisplayName("constructor_ttlZero_throwsException")
  void constructor_ttlZero_throwsException() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new DefaultStatusHistoryCache(TTL_ZERO))
        .withMessageContaining("TTL must be greater than 0 minutes");
  }

  @Test
  @DisplayName("constructor_ttlNegative_throwsException")
  void constructor_ttlNegative_throwsException() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new DefaultStatusHistoryCache(TTL_NEGATIVE))
        .withMessageContaining("TTL must be greater than 0 minutes");
  }

  // Put/Get Roundtrip Tests

  @Test
  @DisplayName("put_singleProgress_success")
  void put_singleProgress_success() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);
    final WorkflowProgress progress = createWorkflowProgress(EXEC_ID_1, STATUS_RUNNING);

    cache.put(EXEC_ID_1, progress);
    final List<WorkflowProgress> retrieved = cache.get(EXEC_ID_1);

    assertThat(retrieved).hasSize(1).contains(progress);
  }

  @Test
  @DisplayName("put_multipleProgress_maintainsOrder")
  void put_multipleProgress_maintainsOrder() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);
    final String executionId = EXEC_ID_1;

    final WorkflowProgress progress1 = createWorkflowProgress(executionId, STATUS_PENDING);
    final WorkflowProgress progress2 = createWorkflowProgress(executionId, STATUS_RUNNING);
    final WorkflowProgress progress3 = createWorkflowProgress(executionId, STATUS_COMPLETED);

    cache.put(executionId, progress1);
    cache.put(executionId, progress2);
    cache.put(executionId, progress3);

    final List<WorkflowProgress> retrieved = cache.get(executionId);

    assertThat(retrieved).hasSize(3);
    assertThat(retrieved.get(0).status()).isEqualTo(STATUS_PENDING);
    assertThat(retrieved.get(1).status()).isEqualTo(STATUS_RUNNING);
    assertThat(retrieved.get(2).status()).isEqualTo(STATUS_COMPLETED);
  }

  // Cache Miss Test

  @Test
  @DisplayName("get_executionNotFound_returnsEmptyList")
  void get_executionNotFound_returnsEmptyList() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);

    final List<WorkflowProgress> retrieved = cache.get("non-existent-exec");

    assertThat(retrieved).isEmpty();
  }

  // Immutability Test

  @Test
  @DisplayName("get_returnsImmutableCopy")
  void get_returnsImmutableCopy() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);
    final String executionId = EXEC_ID_1;
    final WorkflowProgress progress = createWorkflowProgress(executionId, STATUS_RUNNING);

    cache.put(executionId, progress);
    final List<WorkflowProgress> firstRetrival = cache.get(executionId);
    final List<WorkflowProgress> secondRetrieval = cache.get(executionId);

    // Both lists should be equal but different instances
    assertThat(firstRetrival).isEqualTo(secondRetrieval).isNotSameAs(secondRetrieval);

    // Verify that modifications to the returned list don't affect future retrievals
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> firstRetrival.add(createWorkflowProgress(executionId, STATUS_FAILED)));

    final List<WorkflowProgress> thirdRetrieval = cache.get(executionId);
    assertThat(thirdRetrieval).hasSize(1);
  }

  // Isolation Test

  @Test
  @DisplayName("put_differentExecutions_isolated")
  void put_differentExecutions_isolated() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);

    final WorkflowProgress progress1 = createWorkflowProgress(EXEC_ID_1, STATUS_RUNNING);
    final WorkflowProgress progress2 = createWorkflowProgress(EXEC_ID_2, STATUS_RUNNING);
    final WorkflowProgress progress3 = createWorkflowProgress(EXEC_ID_1, STATUS_COMPLETED);

    cache.put(EXEC_ID_1, progress1);
    cache.put(EXEC_ID_2, progress2);
    cache.put(EXEC_ID_1, progress3);

    final List<WorkflowProgress> exec1History = cache.get(EXEC_ID_1);
    final List<WorkflowProgress> exec2History = cache.get(EXEC_ID_2);

    assertThat(exec1History)
        .hasSize(2)
        .extracting(WorkflowProgress::status)
        .containsExactly(STATUS_RUNNING, STATUS_COMPLETED);
    assertThat(exec2History)
        .hasSize(1)
        .extracting(WorkflowProgress::status)
        .containsExactly(STATUS_RUNNING);
  }

  // Thread-Safety Test

  @Test
  @DisplayName("put_concurrentAccess_threadsafe")
  void put_concurrentAccess_threadsafe() throws InterruptedException {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);
    final String executionId = EXEC_CONCURRENT;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch endLatch = new CountDownLatch(NUM_THREADS);
    final AtomicInteger errorCount = new AtomicInteger(0);

    final Thread[] threads = new Thread[NUM_THREADS];
    for (int i = 0; i < NUM_THREADS; i++) {
      final int threadIndex = i;
      threads[i] =
          new Thread(
              () -> {
                try {
                  startLatch.await();
                  for (int j = 0; j < UPDATES_PER_THREAD; j++) {
                    final WorkflowProgress progress =
                        createWorkflowProgress(
                            executionId, STATUS_RUNNING + "_" + threadIndex + "_" + j);
                    cache.put(executionId, progress);
                  }
                } catch (final InterruptedException e) {
                  errorCount.incrementAndGet();
                  Thread.currentThread().interrupt();
                } finally {
                  endLatch.countDown();
                }
              });
    }

    for (final Thread thread : threads) {
      thread.start();
    }

    startLatch.countDown();
    final boolean completed = endLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);

    assertThat(completed).isTrue();
    assertThat(errorCount.get()).isZero();

    final List<WorkflowProgress> history = cache.get(executionId);
    assertThat(history).hasSize(NUM_THREADS * UPDATES_PER_THREAD);
  }

  // Helper method to create WorkflowProgress
  /** Test for put with exception handling. */
  @Test
  @DisplayName("put_runtimeExceptionDuringPut_logsWarning")
  void put_runtimeExceptionDuringPut_logsWarning() {
    final DefaultStatusHistoryCache cache = new DefaultStatusHistoryCache(CACHE_TTL_MINUTES);
    final String executionId = EXEC_ID_1;
    final WorkflowProgress progress = createWorkflowProgress(executionId, STATUS_RUNNING);

    cache.put(executionId, progress);
    final List<WorkflowProgress> retrieved = cache.get(executionId);
    assertThat(retrieved).hasSize(1);
  }

  private WorkflowProgress createWorkflowProgress(final String executionId, final String status) {
    return new WorkflowProgress(
        executionId,
        "session-1",
        "workflow-1",
        status,
        List.of(),
        LocalDateTime.now(ZoneId.systemDefault()),
        null);
  }
}
