// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.SessionCreationGuide;
import com.infenia.yukta.mcp.dto.SessionCreationResult;
import com.infenia.yukta.mcp.dto.SessionDetails;
import com.infenia.yukta.mcp.dto.SessionSummary;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Provider for session-related operations. Handles session retrieval, creation, and configuration
 * guidance.
 */
public interface SessionInfoProvider {

  /**
   * Get details of a specific session.
   *
   * @param sessionId the session identifier
   * @return Mono containing session details; errors if the session is unknown
   */
  Mono<SessionDetails> getSessionDetails(String sessionId);

  /**
   * List all available sessions.
   *
   * @return Mono containing the list of session summaries
   */
  Mono<List<SessionSummary>> listSessions();

  /**
   * Get comprehensive instructions for creating a new session.
   *
   * @return session creation guide
   */
  SessionCreationGuide getSessionCreationInstructions();

  /**
   * Create a new session with the provided configuration.
   *
   * @param sessionConfigJson JSON string containing session configuration
   * @return Mono containing the session creation result
   */
  Mono<SessionCreationResult> createSession(String sessionConfigJson);
}
