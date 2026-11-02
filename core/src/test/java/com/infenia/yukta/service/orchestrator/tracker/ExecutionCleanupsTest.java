// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutionCleanupsTest {

  private static final long SECOND = Duration.ofSeconds(1).toNanos();
  private static final String FIRST = "first";

  @Test
  void drainDue_handsOverOnlyTheRunsWhoseTimeIsUpOldestFirst() {
    final ExecutionCleanups cleanups = new ExecutionCleanups(Duration.ofSeconds(10));
    cleanups.schedule(FIRST, 0L);
    cleanups.schedule("second", 5 * SECOND);
    final List<String> handed = new ArrayList<>();

    cleanups.drainDue(9 * SECOND, handed::add);
    assertThat(handed).isEmpty();

    cleanups.drainDue(10 * SECOND, handed::add);
    assertThat(handed).containsExactly(FIRST);

    cleanups.drainDue(14 * SECOND, handed::add);
    assertThat(handed).containsExactly(FIRST);

    cleanups.drainDue(15 * SECOND, handed::add);
    assertThat(handed).containsExactly(FIRST, "second");

    cleanups.drainDue(Duration.ofHours(1).toNanos(), handed::add);
    assertThat(handed).as("nothing is handed over twice").containsExactly(FIRST, "second");
  }

  @Test
  void stop_forgetsTheRunsStillWaiting() {
    final ExecutionCleanups cleanups = new ExecutionCleanups(Duration.ofSeconds(1));
    cleanups.schedule("waiting", 0L);

    cleanups.stop();

    final List<String> handed = new ArrayList<>();
    cleanups.drainDue(Duration.ofHours(1).toNanos(), handed::add);
    assertThat(handed).isEmpty();
  }

  @Test
  void stop_beforeAnySweeperStartedDoesNothingWrong() {
    final ExecutionCleanups cleanups = new ExecutionCleanups(Duration.ofSeconds(1));

    assertThatCode(cleanups::stop).doesNotThrowAnyException();
  }

  @Test
  void startSweeper_sweepsPeriodicallyUntilStopped() {
    final ExecutionCleanups cleanups = new ExecutionCleanups(Duration.ofMillis(200));
    final AtomicInteger sweeps = new AtomicInteger();
    cleanups.startSweeper(sweeps::incrementAndGet);
    try {
      await().atMost(10, TimeUnit.SECONDS).until(() -> sweeps.get() >= 2);
    } finally {
      cleanups.stop();
    }

    await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> true);
    final int atStop = sweeps.get();
    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(5))
        .until(() -> sweeps.get() == atStop);
    assertThat(sweeps.get()).isEqualTo(atStop);
  }

  @Test
  @ExpectedErrorLog("ERROR com.infenia.yukta.service.orchestrator.tracker.ExecutionCleanups - ")
  void startSweeperLogsTheFailingSweepAndKeepsGoing() {
    final ExecutionCleanups cleanups = new ExecutionCleanups(Duration.ofMillis(200));
    final AtomicInteger sweeps = new AtomicInteger();
    cleanups.startSweeper(
        () -> {
          if (sweeps.getAndIncrement() == 0) {
            throw new IllegalStateException("sweep broke once");
          }
        });
    try {
      await().atMost(10, TimeUnit.SECONDS).until(() -> sweeps.get() >= 3);
    } finally {
      cleanups.stop();
    }
    assertThat(sweeps.get()).isGreaterThanOrEqualTo(3);
  }
}
