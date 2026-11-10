// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.Supervisor;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The newest saved states of each supervisor, so a change to its engine settings (for example its
 * rules) can be undone. This is the in-memory history of {@link InMemorySupervisorStore}; a durable
 * store keeps its own.
 */
public final class SupervisorVersions {

  /** Most versions kept per supervisor. */
  public static final int KEPT = 20;

  // each value is an immutable list, replaced whole, so a read needs no lock
  private final Map<String, List<Version>> history = new ConcurrentHashMap<>();

  /**
   * One saved state.
   *
   * @param version the number, counting up from 1
   * @param savedAt when it was saved
   * @param supervisor the supervisor as saved
   */
  public record Version(int version, Instant savedAt, Supervisor supervisor) {}

  /**
   * Remembers a saved supervisor, unless its settings are the newest version's already.
   *
   * @param supervisor the supervisor as saved
   * @param savedAt when it was saved
   * @param keep how many versions to keep
   */
  public void record(final Supervisor supervisor, final Instant savedAt, final int keep) {
    history.compute(
        supervisor.id(),
        (id, versions) -> {
          final List<Version> kept = versions == null ? List.of() : versions;
          final Version newest = kept.isEmpty() ? null : kept.getFirst();
          if (newest != null && newest.supervisor().equals(supervisor)) {
            return kept;
          }
          final Deque<Version> next = new ArrayDeque<>(kept);
          next.addFirst(
              new Version(newest == null ? 1 : newest.version() + 1, savedAt, supervisor));
          while (next.size() > keep) {
            next.removeLast();
          }
          return List.copyOf(next);
        });
  }

  /**
   * The saved states of a supervisor.
   *
   * @param id the supervisor id
   * @return newest first; empty for an unknown supervisor
   */
  public List<Version> list(final String id) {
    return history.getOrDefault(id, List.of());
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
