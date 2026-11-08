// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.service.supervisor.agent.AgentCredentialStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Keeps the hashes of the agent credentials in PostgreSQL (V14). */
public class R2dbcAgentCredentialStore implements AgentCredentialStore {

  private final DatabaseClient client;

  /**
   * Creates the store.
   *
   * @param client runs the statements
   */
  public R2dbcAgentCredentialStore(final DatabaseClient client) {
    this.client = client;
  }

  @Override
  public Flux<Stored> all() {
    return client
        .sql(
            "SELECT supervisor_id, token_hash, expires_at, allowed_addresses FROM agent_credential")
        .map(
            row -> {
              final OffsetDateTime expires = row.get("expires_at", OffsetDateTime.class);
              return new Stored(
                  row.get("supervisor_id", String.class),
                  row.get("token_hash", String.class),
                  expires == null ? null : expires.toInstant(),
                  addressesOf(row.get("allowed_addresses", String.class)));
            })
        .all();
  }

  @Override
  public Mono<Void> save(final Stored credential) {
    final DatabaseClient.GenericExecuteSpec spec =
        client
            .sql(
                """
                INSERT INTO agent_credential (supervisor_id, token_hash, expires_at, allowed_addresses)
                VALUES (:id, :hash, :expires, :addresses)
                ON CONFLICT (supervisor_id) DO UPDATE SET token_hash = EXCLUDED.token_hash,
                  expires_at = EXCLUDED.expires_at,
                  allowed_addresses = EXCLUDED.allowed_addresses, created_at = now()
                """)
            .bind("id", credential.supervisorId())
            .bind("hash", credential.hash())
            .bind("addresses", String.join(",", new java.util.TreeSet<>(credential.addresses())));
    final Instant expires = credential.expiresAt();
    return (expires == null
            ? spec.bindNull("expires", OffsetDateTime.class)
            : spec.bind("expires", expires.atOffset(ZoneOffset.UTC)))
        .then();
  }

  /** The column is {@code NOT NULL DEFAULT ''}, so a stored value is never null. */
  private static Set<String> addressesOf(final String stored) {
    return stored.isBlank() ? Set.of() : Set.of(stored.split(","));
  }

  @Override
  public Mono<Void> delete(final String supervisorId) {
    return client
        .sql("DELETE FROM agent_credential WHERE supervisor_id = :id")
        .bind("id", supervisorId)
        .then();
  }
}
