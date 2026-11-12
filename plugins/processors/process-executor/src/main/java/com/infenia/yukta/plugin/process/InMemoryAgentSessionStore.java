// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Sessions in memory, lost on restart. ponytail: capped at {@value #MAX_SESSIONS}, the least
 * recently used idle one goes first; persist behind {@link AgentSessionStore} if sessions must
 * outlive the server.
 */
public final class InMemoryAgentSessionStore implements AgentSessionStore {

  /** Most sessions kept. */
  static final int MAX_SESSIONS = 1000;

  /** Sessions in least-recently-used order. */
  private final Map<String, State> sessions = new LinkedHashMap<>(16, 0.75f, true);

  @Override
  public synchronized @Nullable Lease tryAcquire(final String key) {
    final State state = sessions.computeIfAbsent(key, k -> new State());
    if (state.busy) {
      return null;
    }
    state.busy = true;
    evict();
    return new Held(state);
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
  private final class Held implements Lease {
    private final State state;
    private boolean closed;

    Held(final State state) {
      this.state = state;
    }

    @Override
    public @Nullable String token() {
      synchronized (InMemoryAgentSessionStore.this) {
        return state.token;
      }
    }

    @Override
    public int turns() {
      synchronized (InMemoryAgentSessionStore.this) {
        return state.turns;
      }
    }

    @Override
    public void complete(final String token) {
      synchronized (InMemoryAgentSessionStore.this) {
        if (token != null && !token.isBlank()) {
          state.token = token;
        }
        state.turns++;
      }
    }

    @Override
    public void close() {
      synchronized (InMemoryAgentSessionStore.this) {
        if (!closed) {
          closed = true;
          state.busy = false;
        }
      }
    }
  }
}
