// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the chat history of {@code SESSION_MEMORY} is kept besides memory, for example in a
 * database, so it survives a restart and is shared by the servers that use it. Calls block, like
 * the file store's, and a failure must not fail the chat: the history then stays in memory.
 */
public interface SessionHistoryStore {

  /**
   * Reads the history of a session.
   *
   * @param sessionId the session id
   * @return the turns ({@code role}, {@code content}), oldest first, or empty if none are stored
   */
  Optional<List<Map<String, String>>> load(String sessionId);

  /**
   * Replaces the history of a session.
   *
   * @param sessionId the session id
   * @param turns the turns to keep, oldest first
   */
  void save(String sessionId, List<Map<String, String>> turns);

  /**
   * Deletes the history of a session, for example on the user's request.
   *
   * @param sessionId the session id
   */
  void delete(String sessionId);
}
