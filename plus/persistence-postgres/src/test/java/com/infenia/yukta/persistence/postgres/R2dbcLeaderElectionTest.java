// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class R2dbcLeaderElectionTest extends PostgresTestSupport {

  private static final Duration LEASE = Duration.ofSeconds(2);
  private static final Duration WAIT = Duration.ofSeconds(15);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private R2dbcLeaderElection first;
  private R2dbcLeaderElection second;

  private R2dbcLeaderElection election(final String name) {
    return new R2dbcLeaderElection(db, name, LEASE, meters);
  }

  @AfterEach
  void stopAll() {
    if (first != null) {
      first.stop();
    }
    if (second != null) {
      second.stop();
    }
  }

  @Test
  void oneServerHoldsTheLease_theOtherWaits_andTakesOverWhenTheHolderStopsGracefully() {
    final AtomicInteger takeovers = new AtomicInteger();
    first = election("one");
    final SimpleMeterRegistry standbyMeters = new SimpleMeterRegistry();
    second = new R2dbcLeaderElection(db, "two", LEASE, standbyMeters);
    second.onTakeover(takeovers::incrementAndGet);
    first.start();
    await().atMost(WAIT).until(first::isLeader);
    second.start();

    // the standby does not take it while the holder renews
    await().pollDelay(Duration.ofSeconds(3)).atMost(WAIT).until(() -> true);
    assertThat(first.isLeader()).isTrue();
    assertThat(second.isLeader()).isFalse();
    assertThat(first.fencingToken()).isEqualTo(1);
    assertThat(meters.get("yukta.leader").gauge().value()).isEqualTo(1.0);
    assertThat(standbyMeters.get("yukta.leader").gauge().value()).isZero();

    first.stop();

    await().atMost(WAIT).until(second::isLeader);
    await().atMost(WAIT).until(() -> takeovers.get() == 1);
    assertThat(second.fencingToken()).isEqualTo(2);
    assertThat(first.isLeader()).isFalse();
    assertThat(first.isRunning()).isFalse();
  }

  @Test
  void crashedHolder_isReplacedAfterItsLease() {
    first = election("one");
    second = election("two");
    first.start();
    await().atMost(WAIT).until(first::isLeader);
    second.start();

    first.abandon();

    await().atMost(WAIT).until(second::isLeader);
    assertThat(second.fencingToken()).isEqualTo(2);
    await().atMost(WAIT).until(() -> !first.isLeader());
  }

  @Test
  void unreachableDatabase_endsLeadershipAfterOwnLease() {
    first = election("one");
    first.start();
    await().atMost(WAIT).until(first::isLeader);

    db.sql("ALTER TABLE leader_lease RENAME TO leader_lease_gone").fetch().rowsUpdated().block();
    try {
      await().atMost(WAIT).until(() -> !first.isLeader());
    } finally {
      db.sql("ALTER TABLE leader_lease_gone RENAME TO leader_lease").fetch().rowsUpdated().block();
    }

    // and takes the lease back when the database answers again
    await().atMost(WAIT).until(first::isLeader);
    assertThat(first.isLeader()).isTrue();
  }

  @Test
  void actionRegisteredByLeader_runsAtOnce() {
    first = election("one");
    first.start();
    first.start();
    await().atMost(WAIT).until(first::isLeader);
    final AtomicInteger ran = new AtomicInteger();

    first.onTakeover(ran::incrementAndGet);

    assertThat(ran).hasValue(1);
    assertThat(first.recoversOnTakeover()).isTrue();
    first.stop();
    first.stop();
  }

  @Test
  void failingTakeoverAction_doesNotStopOthers() {
    final AtomicInteger ran = new AtomicInteger();
    first = election("one");
    first.onTakeover(
        () -> {
          throw new IllegalStateException("boom");
        });
    first.onTakeover(ran::incrementAndGet);

    first.start();

    await().atMost(WAIT).until(() -> ran.get() == 1);
    assertThat(first.isRunning()).isTrue();
  }

  @Test
  void leaseTakenByAnother_endsLeadership() {
    first = election("one");
    first.start();
    await().atMost(WAIT).until(first::isLeader);

    db.sql("UPDATE leader_lease SET holder = 'intruder', expires_at = now() + interval '1 hour'")
        .fetch()
        .rowsUpdated()
        .block();

    await().atMost(WAIT).until(() -> !first.isLeader());
    assertThat(first.isRunning()).isTrue();
  }

  @Test
  void stopThatCannotReleaseTheLease_orIsInterrupted_stillStops() {
    first = election("one");
    first.start();
    await().atMost(WAIT).until(first::isLeader);
    db.sql("ALTER TABLE leader_lease RENAME TO leader_lease_gone").fetch().rowsUpdated().block();
    try {
      first.stop();
    } finally {
      db.sql("ALTER TABLE leader_lease_gone RENAME TO leader_lease").fetch().rowsUpdated().block();
    }
    assertThat(first.isRunning()).isFalse();

    second = election("two");
    second.start();
    await().atMost(WAIT).until(second::isLeader);
    Thread.currentThread().interrupt();
    try {
      second.stop();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
