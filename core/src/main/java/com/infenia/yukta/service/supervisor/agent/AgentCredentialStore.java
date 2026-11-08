// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps the agent credentials (only their hashes) across a restart. */
public interface AgentCredentialStore {

  /**
   * A stored credential.
   *
   * @param supervisorId the supervisor it belongs to
   * @param hash the SHA-256 of the token, in hex
   * @param expiresAt when it stops working, or {@code null}
   */
  record Stored(String supervisorId, String hash, @Nullable Instant expiresAt) {}

  /**
   * Every stored credential.
   *
   * @return the credentials
   */
  Flux<Stored> all();

  /**
   * Stores the credential of a supervisor, replacing its earlier one.
   *
   * @param credential the credential
   * @return completes when stored
   */
  Mono<Void> save(Stored credential);

  /**
   * Removes the credential of a supervisor.
   *
   * @param supervisorId the supervisor
   * @return completes when removed
   */
  Mono<Void> delete(String supervisorId);
}
