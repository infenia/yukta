// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.Supervisor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The newest saved states of each supervisor, so a change to its engine settings (for example its
 * rules) can be undone. In memory: the history starts again after a restart.
 */
public final class SupervisorVersions {

  /** Most versions kept per supervisor. */
  public static final int KEPT = 20;

  /**
   * One saved state.
   *
   * @param version the number, counting up from 1
   * @param savedAt when it was saved
   * @param supervisor the supervisor as saved
   */
  public record Version(int version, Instant savedAt, Supervisor supervisor) {}

  private final Clock clock;
  private final Map<String, Deque<Version>> history = new ConcurrentHashMap<>();

  /** Creates the history with the system clock. */
  public SupervisorVersions() {
    this(Clock.systemUTC());
  }

  /**
   * Creates the history.
   *
   * @param clock tells the time of a save
   */
  public SupervisorVersions(final Clock clock) {
    this.clock = clock;
  }

  /**
   * Remembers a saved supervisor, unless its settings are the newest version's already.
   *
   * @param supervisor the supervisor as saved
   */
  public void record(final Supervisor supervisor) {
    history.compute(
        supervisor.id(),
        (id, versions) -> {
          final Deque<Version> kept = versions == null ? new ArrayDeque<>() : versions;
          final Version newest = kept.peekFirst();
          if (newest == null || !newest.supervisor().equals(supervisor)) {
            kept.addFirst(
                new Version(
                    newest == null ? 1 : newest.version() + 1, clock.instant(), supervisor));
            while (kept.size() > KEPT) {
              kept.removeLast();
            }
          }
          return kept;
        });
  }

  /**
   * The saved states of a supervisor.
   *
   * @param id the supervisor id
   * @return newest first; empty for an unknown supervisor
   */
  public List<Version> list(final String id) {
    final Deque<Version> versions = history.get(id);
    if (versions == null) {
      return List.of();
    }
    synchronized (versions) {
      return List.copyOf(versions);
    }
  }

  /**
   * One saved state.
   *
   * @param id the supervisor id
   * @param version the number
   * @return the version, if still kept
   */
  public Optional<Version> find(final String id, final int version) {
    return list(id).stream().filter(v -> v.version() == version).findFirst();
  }

  /**
   * Forgets a deleted supervisor.
   *
   * @param id the supervisor id
   */
  public void forget(final String id) {
    history.remove(id);
  }
}
