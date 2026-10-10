// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * In-memory chat history per session id.
 *
 * <p>Turns are {@code {role, content}} maps, the shape the llm-call plugin accepts. To persist
 * history, replace this bean with a subclass backed by a database or file store.
 */
@Service
@NoArgsConstructor
public class SessionStore {

  /** Turns by session id, oldest first. */
  // ponytail: no eviction, so abandoned sessions stay until restart; add a TTL (e.g. caffeine)
  // if sessions are not bounded
  private final Map<String, List<Map<String, String>>> sessions = new ConcurrentHashMap<>();

  /**
   * Return the most recent turns of a session.
   *
   * @param sessionId the session id
   * @param maxTurns the maximum number of turns to return
   * @return a copy of the last {@code maxTurns} turns, oldest first; empty for an unknown session
   */
  public List<Map<String, String>> history(final String sessionId, final int maxTurns) {
    final List<Map<String, String>> turns = sessions.getOrDefault(sessionId, List.of());
    return List.copyOf(turns.subList(Math.max(0, turns.size() - maxTurns), turns.size()));
  }

  /**
   * Append a turn, dropping the oldest turns beyond {@code maxTurns}.
   *
   * @param sessionId the session id
   * @param role "user" or "assistant"
   * @param content the turn text
   * @param maxTurns the maximum number of turns to retain
   */
  public void append(
      final String sessionId, final String role, final String content, final int maxTurns) {
    sessions.compute(
        sessionId,
        (id, existing) -> {
          final List<Map<String, String>> turns =
              existing == null ? new ArrayList<>() : new ArrayList<>(existing);
          turns.add(Map.of("role", role, "content", content));
          return List.copyOf(turns.subList(Math.max(0, turns.size() - maxTurns), turns.size()));
        });
  }
}
