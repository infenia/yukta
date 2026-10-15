// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The workers that are connected to this control plane. A worker is live while it keeps its lease:
 * every registration and heartbeat renews it for {@code yukta.workers.lease} (default 15s), and a
 * worker that misses it is dropped the next time the registry is looked at. Nothing is kept across
 * a restart; workers register again when they reconnect.
 */
@Component
public class WorkerRegistry {

  /** Live workers by id. */
  private final Map<String, WorkerInfo> workers = new ConcurrentHashMap<>();

  /** How long a registration or heartbeat keeps a worker. */
  private final Duration leaseTime;

  /** Tells the time, so tests can move it. */
  private final Clock clock;

  /**
   * Creates the registry and its meters.
   *
   * @param lease how long a heartbeat keeps a worker
   * @param meters where {@code yukta.workers} and {@code yukta.worker.slots} are registered
   */
  @Autowired
  public WorkerRegistry(
      @Value("${yukta.workers.lease:15s}") final Duration lease, final MeterRegistry meters) {
    this(lease, meters, Clock.systemUTC());
  }

  /* default */ WorkerRegistry(
      final Duration lease, final MeterRegistry meters, final Clock clock) {
    this.leaseTime = lease;
    this.clock = clock;
    Gauge.builder("yukta.workers", this, registry -> registry.list().size())
        .description("Workers connected to the control plane")
        .register(meters);
    Gauge.builder(
            "yukta.worker.slots",
            this,
            registry -> registry.list().stream().mapToInt(WorkerInfo::freeSlots).sum())
        .description("Free node slots across the connected workers")
        .register(meters);
  }

  /**
   * How long a heartbeat keeps a worker.
   *
   * @return the lease
   */
  public Duration lease() {
    return leaseTime;
  }

  /**
   * Adds a worker, or replaces the entry of a worker with the same id.
   *
   * @param registration what the worker announced
   * @return the entry, with its lease running
   * @throws IllegalArgumentException if the id is blank or the capacity is not positive
   */
  public WorkerInfo register(final WorkerRegistration registration) {
    if (registration.workerId() == null || registration.workerId().isBlank()) {
      throw new IllegalArgumentException("workerId is required");
    }
    if (registration.capacity() <= 0) {
      throw new IllegalArgumentException("capacity must be at least 1");
    }
    final Instant now = clock.instant();
    final WorkerInfo info =
        new WorkerInfo(
            registration.workerId(),
            registration.pools(),
            registration.pluginTypes(),
            registration.capacity(),
            0,
            false,
            now,
            now.plus(leaseTime));
    workers.put(info.workerId(), info);
    return info;
  }

  /**
   * Renews a worker's lease and records how many slots it uses.
   *
   * @param workerId the worker
   * @param usedSlots how many node attempts it runs now
   * @return false if the worker is not registered (or its lease had run out), so it must register
   *     again
   */
  public boolean heartbeat(final String workerId, final int usedSlots) {
    return heartbeat(workerId, usedSlots, false);
  }

  /**
   * Renews a worker's lease and records how many slots it uses and whether it is draining.
   *
   * @param workerId the worker
   * @param usedSlots how many node attempts it runs now
   * @param draining true if the worker is stopping and takes no new attempts
   * @return false if the worker is not registered (or its lease had run out), so it must register
   *     again
   */
  public boolean heartbeat(final String workerId, final int usedSlots, final boolean draining) {
    final Instant now = clock.instant();
    final WorkerInfo renewed =
        workers.computeIfPresent(
            workerId,
            (id, info) ->
                info.leaseUntil().isBefore(now)
                    ? null
                    : new WorkerInfo(
                        id,
                        info.pools(),
                        info.pluginTypes(),
                        info.capacity(),
                        Math.max(0, usedSlots),
                        draining,
                        info.registeredAt(),
                        now.plus(leaseTime)));
    return renewed != null;
  }

  /**
   * Removes a worker, for example when its connection closed.
   *
   * @param workerId the worker
   */
  public void remove(final String workerId) {
    workers.remove(workerId);
  }

  /**
   * Removes a worker only if it still has the registration made at {@code registeredAt}. A
   * connection that closes late must not remove the worker that has since registered again on a new
   * one.
   *
   * @param workerId the worker
   * @param registeredAt the {@link WorkerInfo#registeredAt()} of the registration being closed
   */
  public void remove(final String workerId, final Instant registeredAt) {
    workers.computeIfPresent(
        workerId, (id, info) -> info.registeredAt().equals(registeredAt) ? null : info);
  }

  /**
   * Finds one live worker.
   *
   * @param workerId the worker
   * @return the entry, or empty if it is not registered or missed its lease
   */
  public Optional<WorkerInfo> find(final String workerId) {
    return list().stream().filter(info -> info.workerId().equals(workerId)).findFirst();
  }

  /**
   * The live workers; those that missed their lease are dropped first.
   *
   * @return the workers, ordered by id
   */
  public List<WorkerInfo> list() {
    final Instant now = clock.instant();
    workers.values().removeIf(info -> info.leaseUntil().isBefore(now));
    return workers.values().stream().sorted(Comparator.comparing(WorkerInfo::workerId)).toList();
  }
}
