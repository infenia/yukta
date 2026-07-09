// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.dto.response.SessionCreationGuide;
import com.infenia.yukta.dto.response.SessionCreationResponse;
import com.infenia.yukta.dto.response.SessionDetails;
import com.infenia.yukta.dto.response.SessionInfo;
import reactor.core.publisher.Flux;
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
   * @return Mono containing session details
   */
  Mono<SessionDetails> getSessionDetails(String sessionId);

  /**
   * List all available sessions.
   *
   * @return Flux of session information
   */
  Flux<SessionInfo> listSessions();

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
   * @return Mono containing session creation response
   */
  Mono<SessionCreationResponse> createSession(String sessionConfigJson);
}
