// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import org.jspecify.annotations.Nullable;

/**
 * Remembers what a harness needs to continue a conversation, and lets one turn run at a time per
 * session. Kept behind this interface so a persistent store can replace the in-memory one.
 */
public interface AgentSessionStore {

  /**
   * Takes the session for one turn.
   *
   * @param key the session key
   * @return the lease, or null while another turn of the session is running
   */
  @Nullable Lease tryAcquire(String key);

  /** One turn's hold on a session; {@link #close()} frees the session for the next turn. */
  interface Lease extends AutoCloseable {

    /**
     * The token the harness resumes from.
     *
     * @return the token, or null for a new session
     */
    @Nullable String token();

    /**
     * The turns this session has completed.
     *
     * @return the count
     */
    int turns();

    /**
     * Records a finished turn.
     *
     * @param token the token to resume from next time; kept as is when blank
     */
    void complete(String token);

    @Override
    void close();
  }
}
