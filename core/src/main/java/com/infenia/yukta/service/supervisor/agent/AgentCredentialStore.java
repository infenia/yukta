// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import java.time.Instant;
import java.util.Set;
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
   * @param addresses the source addresses it may be used from; empty for any
   */
  record Stored(
      String supervisorId, String hash, @Nullable Instant expiresAt, Set<String> addresses) {

    /** Keeps an immutable copy of the addresses. */
    public Stored {
      addresses = Set.copyOf(addresses);
    }

    /**
     * A credential usable from any address.
     *
     * @param supervisorId the supervisor
     * @param hash the hash of the token
     * @param expiresAt when it stops working, or {@code null}
     */
    public Stored(final String supervisorId, final String hash, final @Nullable Instant expiresAt) {
      this(supervisorId, hash, expiresAt, Set.of());
    }
  }

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
