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
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The credential of an agent, one per supervisor: shown once, kept only as a SHA-256 hash, replaced
 * by issuing again (rotation), cut at once by revoking. It works on the agent API only. In memory:
 * after a restart the credential must be issued again.
 */
@Component
public final class AgentCredentials {

  /** The prefix of a token, so a leaked one is easy to spot. */
  public static final String PREFIX = "yag_";

  private static final int TOKEN_BYTES = 32;

  /**
   * A new credential.
   *
   * @param token the secret to give the agent; it cannot be read again
   * @param expiresAt when it stops working, or {@code null}
   */
  public record Issued(String token, @Nullable Instant expiresAt) {}

  private record Entry(String supervisorId, @Nullable Instant expiresAt) {}

  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Entry> byHash = new ConcurrentHashMap<>();
  private final Map<String, String> hashOf = new ConcurrentHashMap<>();

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
   * Makes a credential for a supervisor; an earlier one stops working.
   *
   * @param supervisorId the supervisor
   * @param ttl how long it works, or {@code null} for no expiry
   * @return the token, shown once
   */
  public Issued issue(final String supervisorId, final @Nullable Duration ttl) {
    final byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    final String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    final Instant expires = ttl == null ? null : clock.instant().plus(ttl);
    final String hash = hash(token);
    final String earlier = hashOf.put(supervisorId, hash);
    if (earlier != null) {
      byHash.remove(earlier);
    }
    byHash.put(hash, new Entry(supervisorId, expires));
    return new Issued(token, expires);
  }

  /**
   * Finds the supervisor a token belongs to.
   *
   * @param token the token an agent presented
   * @return the supervisor id, or empty for an unknown, revoked or expired token
   */
  public Optional<String> authenticate(final @Nullable String token) {
    if (token == null || !token.startsWith(PREFIX)) {
      return Optional.empty();
    }
    return Optional.ofNullable(byHash.get(hash(token)))
        .filter(entry -> entry.expiresAt() == null || entry.expiresAt().isAfter(clock.instant()))
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
    return hash != null && byHash.remove(hash) != null;
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
