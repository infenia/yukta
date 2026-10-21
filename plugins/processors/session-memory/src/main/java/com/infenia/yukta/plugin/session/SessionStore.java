// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.infenia.yukta.plugin.store.SessionHistoryStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Chat history per session id, in memory and, when the application has a {@link
 * SessionHistoryStore} (a database, or the files of the Plus edition), also there, so history
 * survives a restart.
 *
 * <p>Turns are {@code {role, content}} maps, the shape the llm-call plugin accepts. At most {@code
 * yukta.session-memory.max-sessions} sessions (default 10,000) are kept in memory, the least
 * recently used leaving first, and with {@code yukta.session-memory.ttl} a session idle for that
 * long leaves too. With a history store a session that left memory is read back on its next use;
 * with memory only its history is gone.
 */
@Service
public class SessionStore {

  /** Most sessions kept in memory when {@code yukta.session-memory.max-sessions} is not set. */
  private static final long DEFAULT_MAX_SESSIONS = 10_000;

  /** Turns by session id, oldest first. */
  private final Cache<String, List<Map<String, String>>> sessions;

  /** Where the history is kept besides memory, or null. */
  private final @Nullable SessionHistoryStore database;

  /** Creates a store that keeps history in memory only. */
  public SessionStore() {
    this(null, DEFAULT_MAX_SESSIONS, null, Ticker.systemTicker());
  }

  private SessionStore(
      final @Nullable SessionHistoryStore database,
      final long maxSessions,
      final @Nullable Duration ttl,
      final Ticker ticker) {
    this.database = database;
    final Caffeine<Object, Object> builder =
        Caffeine.newBuilder().maximumSize(maxSessions).executor(Runnable::run).ticker(ticker);
    if (ttl != null) {
      builder.expireAfterAccess(ttl);
    }
    this.sessions = builder.build();
  }

  /**
   * Creates a store whose history is kept in a history store.
   *
   * @param database where the history is kept
   */
  public SessionStore(final SessionHistoryStore database) {
    this(database, DEFAULT_MAX_SESSIONS, null, Ticker.systemTicker());
  }

  /**
   * Creates a store with its own limits, keeping history in memory only.
   *
   * @param maxSessions most sessions kept in memory
   * @param ttl how long an unused session stays in memory, or null for no limit
   * @param ticker the clock the time limit uses
   */
  /* default */ SessionStore(
      final long maxSessions, final @Nullable Duration ttl, final Ticker ticker) {
    this(null, maxSessions, ttl, ticker);
  }

  /**
   * Spring constructor: the application's history store if there is one, else memory.
   *
   * @param databases the application's history store, if any
   * @param maxSessions most sessions kept in memory
   * @param ttl how long an unused session stays in memory, or unset for no limit
   */
  @Autowired
  public SessionStore(
      final ObjectProvider<SessionHistoryStore> databases,
      @Value("${yukta.session-memory.max-sessions:10000}") final long maxSessions,
      @Value("${yukta.session-memory.ttl:#{null}}") final @Nullable Duration ttl) {
    this(databases.getIfAvailable(), maxSessions, ttl, Ticker.systemTicker());
  }

  /**
   * Return the most recent turns of a session.
   *
   * @param sessionId the session id
   * @param maxTurns the maximum number of turns to return
   * @return a copy of the last {@code maxTurns} turns, oldest first; empty for an unknown session
   */
  public List<Map<String, String>> history(final String sessionId, final int maxTurns) {
    final List<Map<String, String>> turns =
        sessions.get(sessionId, id -> load(id).orElse(List.of()));
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
    sessions
        .asMap()
        .compute(
            sessionId,
            (id, existing) -> {
              final List<Map<String, String>> turns =
                  new ArrayList<>(existing == null ? load(id).orElse(List.of()) : existing);
              turns.add(Map.of("role", role, "content", content));
              final List<Map<String, String>> kept =
                  List.copyOf(turns.subList(Math.max(0, turns.size() - maxTurns), turns.size()));
              save(id, kept);
              return kept;
            });
  }

  /**
   * Number of sessions kept in memory.
   *
   * @return the cache size
   */
  /* default */ long sessionCount() {
    sessions.cleanUp();
    return sessions.estimatedSize();
  }

  private java.util.Optional<List<Map<String, String>>> load(final String sessionId) {
    return database == null ? java.util.Optional.empty() : database.load(sessionId);
  }

  private void save(final String sessionId, final List<Map<String, String>> turns) {
    if (database != null) {
      database.save(sessionId, turns);
    }
  }
}
