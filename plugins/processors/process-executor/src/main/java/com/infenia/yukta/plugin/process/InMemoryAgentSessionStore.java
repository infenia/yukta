// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Sessions in memory, lost on restart. ponytail: capped at {@value #MAX_SESSIONS}, the least
 * recently used idle one goes first; persist behind {@link AgentSessionStore} if sessions must
 * outlive the server.
 */
public final class InMemoryAgentSessionStore implements AgentSessionStore {

  /** Most sessions kept. */
  /* default */ static final int MAX_SESSIONS = 1000;

  private final ReentrantLock lock = new ReentrantLock();

  /** Sessions in least-recently-used order; guarded by {@link #lock}. */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private final Map<String, State> sessions = new LinkedHashMap<>(16, 0.75f, true);

  @Override
  public @Nullable Lease tryAcquire(final String key) {
    return locked(
        () -> {
          final State state = sessions.computeIfAbsent(key, k -> new State());
          if (state.busy) {
            return null;
          }
          state.busy = true;
          evict();
          return new TurnLease(state);
        });
  }

  private <T> T locked(final Supplier<T> action) {
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private void evict() {
    if (sessions.size() <= MAX_SESSIONS) {
      return;
    }
    final var it = sessions.values().iterator();
    while (it.hasNext() && sessions.size() > MAX_SESSIONS) {
      if (!it.next().busy) {
        it.remove();
      }
    }
  }

  /** What is remembered of one session. */
  private static final class State {
    private boolean busy;
    private @Nullable String token;
    private int turns;
  }

  /** A turn's hold on a session. */
  private final class TurnLease implements Lease {
    private final State state;
    private boolean closed;

    /* default */ TurnLease(final State state) {
      this.state = state;
    }

    @Override
    public @Nullable String token() {
      return locked(() -> state.token);
    }

    @Override
    public int turns() {
      return locked(() -> state.turns);
    }

    @Override
    public void complete(final String token) {
      locked(
          () -> {
            if (token != null && !token.isBlank()) {
              state.token = token;
            }
            state.turns++;
            return null;
          });
    }

    @Override
    public void close() {
      locked(
          () -> {
            if (!closed) {
              closed = true;
              state.busy = false;
            }
            return null;
          });
    }
  }
}
