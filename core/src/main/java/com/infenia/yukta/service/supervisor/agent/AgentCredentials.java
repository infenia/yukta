// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The credential of an agent, one per supervisor: shown once, kept only as a SHA-256 hash, replaced
 * by issuing again (rotation), cut at once by revoking. It works on the agent API only. With an
 * {@link AgentCredentialStore} (PostgreSQL in the Plus edition) the hashes survive a restart.
 */
@Slf4j
@Component
@SuppressWarnings("PMD.TooManyMethods")
public final class AgentCredentials {

  /** The prefix of a token, so a leaked one is easy to spot. */
  public static final String PREFIX = "yag_";

  private static final int TOKEN_BYTES = 32;

  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Entry> byHash = new ConcurrentHashMap<>();
  private final Map<String, String> hashOf = new ConcurrentHashMap<>();

  /** Keeps the hashes across a restart; null where a module provides none. */
  @Nullable private AgentCredentialStore store;

  @Nullable private AgentHub hub;

  /**
   * A new credential.
   *
   * @param token the secret to give the agent; it cannot be read again
   * @param expiresAt when it stops working, or {@code null}
   */
  public record Issued(String token, @Nullable Instant expiresAt) {}

  private record Entry(String supervisorId, @Nullable Instant expiresAt, Set<String> addresses) {}

  /** Creates the store with the system clock. */
  public AgentCredentials() {
    this(Clock.systemUTC());
  }

  /**
   * Creates the store.
   *
   * @param clock tells the time
   */
  public AgentCredentials(final Clock clock) {
    this.clock = clock;
  }

  /**
   * Lets a revoke or rotation end the open streams of the old credential.
   *
   * @param agentHub the hub
   */
  @Autowired(required = false)
  public void setHub(final AgentHub agentHub) {
    this.hub = agentHub;
  }

  /**
   * Sets where the credentials are kept.
   *
   * @param credentialStore the store
   */
  @Autowired(required = false)
  public void setStore(final AgentCredentialStore credentialStore) {
    this.store = credentialStore;
  }

  /** Loads the stored credentials once the application is up. */
  @EventListener(ApplicationReadyEvent.class)
  public void restoreOnReady() {
    restore().subscribe();
  }

  /* default */ Mono<Void> restore() {
    final AgentCredentialStore kept = store;
    return kept == null
        ? Mono.empty()
        : kept.all()
            .filter(c -> live(c.expiresAt()))
            .doOnNext(c -> remember(c.supervisorId(), c.hash(), c.expiresAt(), c.addresses()))
            .then()
            .onErrorResume(
                error -> {
                  log.atWarn().setCause(error).log("Could not load the agent credentials");
                  return Mono.empty();
                });
  }

  private void remember(
      final String supervisorId,
      final String hash,
      final @Nullable Instant expires,
      final Set<String> addresses) {
    final String earlier = hashOf.put(supervisorId, hash);
    if (earlier != null) {
      byHash.remove(earlier);
      changed(supervisorId);
    }
    byHash.put(hash, new Entry(supervisorId, expires, addresses));
  }

  private void persist(final Mono<Void> write, final String supervisorId) {
    write
        .doOnError(
            error ->
                log.atWarn()
                    .setCause(error)
                    .addKeyValue("supervisor", supervisorId)
                    .log("Could not store an agent credential"))
        .onErrorResume(error -> Mono.empty())
        .subscribe();
  }

  /**
   * Makes a credential for a supervisor; an earlier one stops working.
   *
   * @param supervisorId the supervisor
   * @param ttl how long it works, or {@code null} for no expiry
   * @return the token, shown once
   */
  public Issued issue(final String supervisorId, final @Nullable Duration ttl) {
    return issue(supervisorId, ttl, Set.of());
  }

  /**
   * Makes a credential that works only from some addresses.
   *
   * @param supervisorId the supervisor
   * @param ttl how long it works, or {@code null} for no expiry
   * @param addresses the source addresses it may be used from; empty for any
   * @return the token, shown once
   */
  public Issued issue(
      final String supervisorId, final @Nullable Duration ttl, final Set<String> addresses) {
    final byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    final String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    final Instant expires = ttl == null ? null : clock.instant().plus(ttl);
    final String hash = hash(token);
    remember(supervisorId, hash, expires, addresses);
    if (store != null) {
      persist(
          store.save(new AgentCredentialStore.Stored(supervisorId, hash, expires, addresses)),
          supervisorId);
    }
    return new Issued(token, expires);
  }

  /**
   * Finds the supervisor a token belongs to.
   *
   * @param token the token an agent presented
   * @return the supervisor id, or empty for an unknown, revoked or expired token
   */
  public Optional<String> authenticate(final @Nullable String token) {
    return authenticate(token, null);
  }

  /**
   * Finds the supervisor a token belongs to, when it is used from an allowed address.
   *
   * @param token the token an agent presented
   * @param remote the address the request came from, or {@code null} if unknown
   * @return the supervisor id, or empty for an unknown, revoked or expired token, or a credential
   *     tied to addresses that does not include {@code remote}
   */
  public Optional<String> authenticate(
      final @Nullable String token, final @Nullable String remote) {
    if (token == null || !token.startsWith(PREFIX)) {
      return Optional.empty();
    }
    return Optional.ofNullable(byHash.get(hash(token)))
        .filter(entry -> live(entry.expiresAt()))
        .filter(
            entry ->
                entry.addresses().isEmpty() || remote != null && entry.addresses().contains(remote))
        .map(Entry::supervisorId);
  }

  /**
   * Cuts a credential.
   *
   * @param supervisorId the supervisor
   * @return true if it had one
   */
  public boolean revoke(final String supervisorId) {
    final String hash = hashOf.remove(supervisorId);
    if (store != null && hash != null) {
      persist(store.delete(supervisorId), supervisorId);
    }
    if (hash != null) {
      changed(supervisorId);
    }
    return hash != null && byHash.remove(hash) != null;
  }

  private void changed(final String supervisorId) {
    final AgentHub agents = hub;
    if (agents != null) {
      agents.credentialChanged(supervisorId);
    }
  }

  /**
   * Whether a supervisor has a credential.
   *
   * @param supervisorId the supervisor
   * @return true if it has one
   */
  public boolean has(final String supervisorId) {
    return hashOf.containsKey(supervisorId);
  }

  private boolean live(final @Nullable Instant expiresAt) {
    return expiresAt == null || expiresAt.isAfter(clock.instant());
  }

  private static String hash(final String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }
}
