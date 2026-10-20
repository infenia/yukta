// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import com.infenia.yukta.plugin.store.SessionHistoryStore;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the chat history of each session in one small JSON file, replaced atomically on every turn,
 * so history survives a restart. Active when {@code yukta.session-memory.dir} is set.
 */
@Slf4j
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public class FileSessionHistoryStore implements SessionHistoryStore {

  /** Reads and writes the session files. */
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** The stored turns of one session. */
  private static final TypeReference<List<Map<String, String>>> TURNS = new TypeReference<>() {};

  /** Directory of the session files. */
  private final Path dir;

  /**
   * Creates the store.
   *
   * @param dir directory for the session files
   */
  public FileSessionHistoryStore(final String dir) {
    this.dir = Path.of(dir);
  }

  @Override
  public Optional<List<Map<String, String>>> load(final String sessionId) {
    final Path file = file(sessionId);
    Optional<List<Map<String, String>>> turns = Optional.empty();
    if (Files.exists(file)) {
      try {
        turns = Optional.of(MAPPER.readValue(Files.readString(file), TURNS));
      } catch (final IOException | RuntimeException e) {
        log.atWarn().setCause(e).log("Could not read the history of a session; starting empty");
      }
    }
    return turns;
  }

  @Override
  public void save(final String sessionId, final List<Map<String, String>> turns) {
    try {
      Files.createDirectories(dir);
      final Path file = file(sessionId);
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
  private Path file(final String sessionId) {
    return dir.resolve(URLEncoder.encode(sessionId, StandardCharsets.UTF_8) + ".json");
  }
}
