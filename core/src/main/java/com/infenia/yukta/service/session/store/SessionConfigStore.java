// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session.store;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.validation.ProjectPath;
import com.infenia.yukta.validation.SessionId;
import jakarta.validation.Valid;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Interface for managing session configuration with runtime overrides. */
@SuppressWarnings("PMD.LinguisticNaming")
public interface SessionConfigStore {

  /**
   * Apply complete session configuration data.
   *
   * <p>This method stores the entire session configuration, allowing implementations to optimize
   * storage (e.g., file-based stores can write a single JSON document, in-memory stores can update
   * all maps atomically).
   *
   * @param data the complete session configuration data
   * @return Mono that completes when the configuration is stored
   */
  Mono<Void> applySessionConfig(@Valid SessionConfigData data);

  /**
   * Get the external project path for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the project path
   */
  Mono<String> getProjectPath(@SessionId String sessionId);

  /**
   * Set the external project path override for a session.
   *
   * @param sessionId the session identifier
   * @param path the project path
   * @return Mono that completes when the path is set
   */
  Mono<Void> setProjectPath(@SessionId String sessionId, @ProjectPath String path);

  /**
   * Get the execution timeout in seconds for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the timeout
   */
  Mono<Long> getExecutionTimeout(@SessionId String sessionId);

  /**
   * Get the modified files log directory for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the log directory
   */
  Mono<String> getFileLogDir(String sessionId);

  /**
   * Get the results log directory for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the log directory
   */
  Mono<String> getResultLogDir(String sessionId);

  /**
   * Get the description for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the description
   */
  Mono<String> getDescription(@SessionId String sessionId);

  /**
   * Set the description for a session.
   *
   * @param sessionId the session identifier
   * @param description the description
   * @return Mono that completes when the description is set
   */
  Mono<Void> setDescription(@SessionId String sessionId, String description);

  /**
   * Get the initiator for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the initiator
   */
  Mono<String> getInitiator(@SessionId String sessionId);

  /**
   * Set the initiator for a session.
   *
   * @param sessionId the session identifier
   * @param initiator the initiator
   * @return Mono that completes when the initiator is set
   */
  Mono<Void> setInitiator(@SessionId String sessionId, String initiator);

  /**
   * Get the initiated time for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the initiated time
   */
  Mono<String> getInitiatedTime(@SessionId String sessionId);

  /**
   * Set the initiated time for a session.
   *
   * @param sessionId the session identifier
   * @param initiatedTime the initiated time
   * @return Mono that completes when the initiated time is set
   */
  Mono<Void> setInitiatedTime(@SessionId String sessionId, String initiatedTime);

  /**
   * Get the tags for a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the tags
   */
  Mono<Map<String, String>> getTags(@SessionId String sessionId);

  /**
   * Set the tags for a session.
   *
   * @param sessionId the session identifier
   * @param tags the tags
   * @return Mono that completes when the tags are set
   */
  Mono<Void> setTags(@SessionId String sessionId, Map<String, String> tags);

  /**
   * Get all configurations for a session as a map.
   *
   * @param sessionId the session identifier
   * @return Mono containing map of configurations
   */
  Mono<Map<String, Object>> getAllConfigs(@SessionId String sessionId);

  /**
   * Get all available session IDs.
   *
   * <p>Retrieves all session identifiers that have configurations stored in the system, regardless
   * of whether they are currently active or not.
   *
   * @return Flux of session IDs
   */
  Flux<String> getSessionIds();
}
