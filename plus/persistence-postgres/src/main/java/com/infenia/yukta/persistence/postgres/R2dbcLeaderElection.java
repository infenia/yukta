// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.leader.LeaderElection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Leader election with a lease row in PostgreSQL ({@code yukta.ha.mode=postgres}). The holder
 * renews the lease three times per lease period; a standby takes it over once it has expired, in
 * one atomic statement, and the fencing number grows with every change of holder.
 *
 * <p>This server counts itself the leader only while its own last renewal is younger than the
 * lease, measured on its own clock from before the statement was sent, so a server that cannot
 * reach the database stops admitting runs before another one can take over. A graceful stop
 * releases the lease so a standby does not have to wait for it. Recovery of the runs the earlier
 * leader left running happens on takeover ({@link LeaderElection#recoversOnTakeover()}).
 */
@Slf4j
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class R2dbcLeaderElection implements LeaderElection, SmartLifecycle {

  /** Longest a statement may take. */
  private static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(10);

  /** Name of the lease row. */
  private static final String NAME = "leader";

  /** Runs the statements. */
  private final DatabaseClient client;

  /** This server's id in the lease row. */
  private final String instanceId;

  /** How long a renewal keeps the lease. */
  private final Duration lease;

  /** What to do on each takeover. */
  private final List<Runnable> takeovers = new CopyOnWriteArrayList<>();

  /** When (on this server's nanosecond clock) its leadership ends unless renewed. */
  private final AtomicLong validUntil = new AtomicLong();

  /** The fencing number of the last lease this server held. */
  private final AtomicLong fencing = new AtomicLong();

  /** Whether the election is running. */
  private final AtomicBoolean running = new AtomicBoolean();

  /** Whether this server held the lease at the last renewal, to notice a takeover. */
  private final AtomicBoolean wasLeader = new AtomicBoolean();

  /** The renewal loop. */
  private Disposable renewal;

  /**
   * Creates the election; it starts with the application.
   *
   * @param client runs the statements
   * @param instanceId this server's id
   * @param lease how long a renewal keeps the lease
   * @param meters where the {@code yukta.leader} gauge is registered
   */
  public R2dbcLeaderElection(
      final DatabaseClient client,
      final String instanceId,
      final Duration lease,
      final MeterRegistry meters) {
    this.client = client;
    this.instanceId = instanceId;
    this.lease = lease;
    Gauge.builder("yukta.leader", this, election -> election.isLeader() ? 1 : 0)
        .description("1 while this server is the leader")
        .register(meters);
  }

  @Override
  public boolean isLeader() {
    return System.nanoTime() - validUntil.get() < 0;
  }

  @Override
  public boolean recoversOnTakeover() {
    return true;
  }

  @Override
  public void onTakeover(final Runnable action) {
    takeovers.add(action);
    if (isLeader()) {
      action.run();
    }
  }

  /**
   * The fencing number of the lease this server held last; it grows with every change of holder, so
   * a store can refuse writes from a server that lost the lease.
   *
   * @return the fencing number, 0 before this server was ever the leader
   */
  public long fencingToken() {
    return fencing.get();
  }

  @Override
  public void start() {
    if (running.compareAndSet(false, true)) {
      renewal =
          Flux.interval(Duration.ZERO, lease.dividedBy(3))
              .onBackpressureDrop()
              // defer: an exception while the statement is being built must not end the loop
              .concatMap(
                  _ ->
                      Mono.defer(this::renew)
                          .onErrorResume(
                              error -> {
                                log.atWarn()
                                    .setCause(error)
                                    .log("Could not renew the leader lease");
                                return Mono.empty();
                              }),
                  1)
              .subscribe();
    }
  }

  @Override
  public void stop() {
    if (running.compareAndSet(true, false)) {
      renewal.dispose();
      release();
    }
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  /** Renews or takes the lease; a database that does not answer leaves the old lease to run out. */
  private Mono<Void> renew() {
    final long sentAt = System.nanoTime();
    return client
        .sql(
            """
            INSERT INTO leader_lease (name, holder, fencing, expires_at)
            VALUES (:n, :me, 1, now() + make_interval(secs => :secs))
            ON CONFLICT (name) DO UPDATE SET
              holder = :me,
              fencing = CASE WHEN leader_lease.holder = :me
                             THEN leader_lease.fencing ELSE leader_lease.fencing + 1 END,
              expires_at = now() + make_interval(secs => :secs)
            WHERE leader_lease.holder = :me OR leader_lease.expires_at < now()
            RETURNING fencing
            """)
        .bind("n", NAME)
        .bind("me", instanceId)
        .bind("secs", lease.toMillis() / 1000.0)
        .map(row -> row.get("fencing", Long.class))
        .one()
        .doOnNext(held -> held(held, sentAt))
        .switchIfEmpty(Mono.fromRunnable(this::lost))
        .timeout(STATEMENT_TIMEOUT)
        .onErrorResume(
            error -> {
              log.atWarn().setCause(error).log("Could not renew the leader lease");
              return Mono.empty();
            })
        .then();
  }

  private void held(final long fencingToken, final long sentAt) {
    fencing.set(fencingToken);
    validUntil.set(sentAt + lease.toNanos());
    if (wasLeader.compareAndSet(false, true)) {
      log.atInfo().addKeyValue("fencing", fencingToken).log("This server is the leader");
      Schedulers.boundedElastic()
          .schedule(
              () -> {
                for (final Runnable action : takeovers) {
                  try {
                    action.run();
                  } catch (final RuntimeException e) {
                    log.atError().setCause(e).log("A takeover action failed");
                  }
                }
              });
    }
  }

  private void lost() {
    validUntil.set(0);
    if (wasLeader.compareAndSet(true, false)) {
      log.atWarn().log("This server lost the leader lease");
    }
  }

  /** Gives the lease up at once, so a standby can take it over without waiting. */
  private void release() {
    validUntil.set(0);
    wasLeader.set(false);
    try {
      client
          .sql(
              "UPDATE leader_lease SET expires_at = now() - interval '1 second'"
                  + " WHERE name = :n AND holder = :me")
          .bind("n", NAME)
          .bind("me", instanceId)
          .fetch()
          .rowsUpdated()
          .toFuture()
          .get(STATEMENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (final java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException e) {
      log.atWarn().setCause(e).log("Could not release the leader lease");
    }
  }

  /** Stops renewing without giving the lease up, as a crash would; for tests. */
  /* default */ void abandon() {
    running.set(false);
    renewal.dispose();
  }
}
