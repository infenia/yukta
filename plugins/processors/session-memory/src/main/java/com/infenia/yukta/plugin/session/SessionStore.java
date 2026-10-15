// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import com.infenia.yukta.plugin.store.SessionHistoryStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chat history per session id, in memory and, when {@code yukta.session-memory.dir} is set, also on
 * disk (one small JSON file per session, replaced atomically on every turn), so history survives a
 * restart.
 *
 * <p>Turns are {@code {role, content}} maps, the shape the llm-call plugin accepts. A session that
 * was read once stays in memory until restart. A file that cannot be read is logged and treated as
 * an empty history.
 */
@Slf4j
@Service
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.LawOfDemeter"})
public class SessionStore {

  /** Reads and writes the session files. */
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** The stored turns of one session. */
  private static final TypeReference<List<Map<String, String>>> TURNS = new TypeReference<>() {};

  /** Turns by session id, oldest first. */
  // ponytail: no eviction, so abandoned sessions stay until restart; add a TTL (e.g. caffeine)
  // if sessions are not bounded
  private final Map<String, List<Map<String, String>>> sessions = new ConcurrentHashMap<>();

  /** Directory of the session files, or null to keep history in memory only. */
  private final @Nullable Path dir;

  /** A database for the history, or null; used instead of the files when present. */
  private final @Nullable SessionHistoryStore database;

  /** Creates a store that keeps history in memory only. */
  public SessionStore() {
    this("");
  }

  /**
   * Creates a store whose history is kept in a database.
   *
   * @param database where the history is kept
   */
  public SessionStore(final SessionHistoryStore database) {
    this.dir = null;
    this.database = database;
  }

  /**
   * Creates the store.
   *
   * @param dir directory for the session files, or empty to keep history in memory only
   */
  public SessionStore(final String dir) {
    this.dir = dir.isBlank() ? null : Path.of(dir);
    this.database = null;
  }

  /**
   * Spring constructor: a database for the history if the application has one, else the files of
   * {@code yukta.session-memory.dir}, else memory.
   *
   * @param dir directory for the session files, or empty
   * @param databases the application's database store, if any
   */
  @Autowired
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification = "A malformed directory fails at startup, before any session exists")
  public SessionStore(
      @Value("${yukta.session-memory.dir:}") final String dir,
      final ObjectProvider<SessionHistoryStore> databases) {
    this.database = databases.getIfAvailable();
    this.dir = dir.isBlank() ? null : Path.of(dir);
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
        sessions.computeIfAbsent(sessionId, id -> load(id).orElse(List.of()));
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
              new ArrayList<>(existing == null ? load(id).orElse(List.of()) : existing);
          turns.add(Map.of("role", role, "content", content));
          final List<Map<String, String>> kept =
              List.copyOf(turns.subList(Math.max(0, turns.size() - maxTurns), turns.size()));
          save(id, kept);
          return kept;
        });
  }

  private java.util.Optional<List<Map<String, String>>> load(final String sessionId) {
    java.util.Optional<List<Map<String, String>>> turns = java.util.Optional.empty();
    if (database != null) {
      turns = database.load(sessionId);
    } else if (dir != null) {
      final Path file = file(dir, sessionId);
      if (Files.exists(file)) {
        try {
          turns = java.util.Optional.of(MAPPER.readValue(Files.readString(file), TURNS));
        } catch (final IOException | RuntimeException e) {
          log.atWarn().setCause(e).log("Could not read the history of a session; starting empty");
        }
      }
    }
    return turns;
  }

  private void save(final String sessionId, final List<Map<String, String>> turns) {
    if (database != null) {
      database.save(sessionId, turns);
    } else if (dir != null) {
      saveToFile(dir, sessionId, turns);
    }
  }

  private static void saveToFile(
      final Path dir, final String sessionId, final List<Map<String, String>> turns) {
    try {
      Files.createDirectories(dir);
      final Path file = file(dir, sessionId);
      final Path tmp = Files.createTempFile(dir, "session", ".tmp");
      try {
        Files.writeString(tmp, MAPPER.writeValueAsString(turns), StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
          channel.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(tmp);
      }
    } catch (final IOException | RuntimeException e) {
      // history stays in memory; losing durability must not fail the chat
      log.atError().setCause(e).log("Could not store the history of a session");
    }
  }

  /** The session id is encoded, so it cannot name a path outside the directory. */
  private static Path file(final Path dir, final String sessionId) {
    return dir.resolve(URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + ".json");
  }
}
