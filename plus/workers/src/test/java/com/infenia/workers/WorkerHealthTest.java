// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.workers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkerHealthTest {

  private final AtomicLong now = new AtomicLong(1_000);
  private final WorkerHealth health = new WorkerHealth(now::get);

  private boolean failTimes(final String workerId, final int times) {
    boolean quarantined = false;
    for (int failure = 0; failure < times; failure++) {
      now.incrementAndGet();
      quarantined = health.failed(workerId, "T");
    }
    return quarantined;
  }

  @Test
  void workerFailingWhileAnotherSucceeds_isQuarantined_untilTheCoolDownEnds() {
    assertThat(failTimes("bad", 1)).isFalse();
    health.succeeded("good", "T");
    assertThat(failTimes("bad", WorkerHealth.FAILURES - 2)).isFalse();
    assertThat(failTimes("bad", 1)).isTrue();
    assertThat(health.quarantined("bad")).isTrue();
    assertThat(health.quarantined("good")).isFalse();

    now.addAndGet(WorkerHealth.COOL_DOWN.toNanos());
    assertThat(health.quarantined("bad")).isFalse();
    assertThat(health.quarantined("bad")).isFalse();
  }

  @Test
  void everyWorkerFailing_quarantinesNone() {
    assertThat(failTimes("a", WorkerHealth.FAILURES * 2)).isFalse();
    assertThat(failTimes("b", WorkerHealth.FAILURES * 2)).isFalse();
    assertThat(health.quarantined("a")).isFalse();
  }

  @Test
  void workersOwnEarlierSuccess_doesNotQuarantineIt() {
    health.succeeded("bad", "T");
    now.incrementAndGet();
    assertThat(failTimes("bad", WorkerHealth.FAILURES)).isFalse();
    assertThat(health.quarantined("bad")).isFalse();
  }

  @Test
  void successBeforeTheStreak_orOfTheWorkerItself_doesNotCount() {
    health.succeeded("good", "T");
    health.succeeded("good", "OTHER");
    now.incrementAndGet();
    assertThat(failTimes("bad", WorkerHealth.FAILURES)).isFalse();

    health.succeeded("bad", "T");
    assertThat(failTimes("bad", WorkerHealth.FAILURES - 1)).isFalse();
  }
}
