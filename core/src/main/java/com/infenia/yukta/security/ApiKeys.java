// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;

/**
 * The API keys of the server, shared by the REST and the MCP filter. Keys come from {@code
 * yukta.api-key} (one key that may do everything) and {@code yukta.api-keys} (named keys with a
 * role, an optional list of sessions and an optional expiry). A configured key is plain text or
 * {@code sha256:<hex>} (the hash of the key), so the file need not hold the secret; listing several
 * keys at once lets a key be rotated without downtime.
 */
@Slf4j
public final class ApiKeys {

  /** The Authorization scheme prefix. */
  private static final String BEARER = "Bearer ";

  /** Prefix of a key given as a hash. */
  private static final String HASH_PREFIX = "sha256:";

  /** Plain-text keys shorter than this are reported at startup. */
  private static final int MIN_KEY_LENGTH = 32;

  /** A SHA-256 digest in hex. */
  private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");

  /** Written in {@code sessions} to say a key reaches every session. */
  private static final String ALL_SESSIONS = "*";

  /** Name the single {@code yukta.api-key} goes by. */
  private static final String DEFAULT_NAME = "default";

  /** The configured keys. */
  private final List<Key> keys;

  /** Whether named keys exist, so a key name is the actor of a request. */
  private final boolean namedKeys;

  /**
   * A key as written in the configuration; {@code expires} (an ISO-8601 instant) is optional, and
   * after it the key no longer matches.
   */
  public record KeyEntry(
      String name, String key, ApiRole role, List<String> sessions, @Nullable Instant expires) {

    /** Copies the session list. */
    public KeyEntry {
      sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }
  }

  /**
   * A key that matched a request.
   *
   * @param name the name used in the audit as {@code key:<name>}
   * @param role what the key may do
   * @param sessions the only sessions the key may reach; empty means all
   */
  public record Match(String name, ApiRole role, Set<String> sessions) {

    /** Copies the session set. */
    public Match {
      sessions = Set.copyOf(sessions);
    }

    /**
     * Whether the key may reach the session.
     *
     * @param sessionId the session
     * @return true if unrestricted or listed
     */
    public boolean reaches(final String sessionId) {
      return sessions.isEmpty() || sessions.contains(sessionId);
    }
  }

  /** A key by the hex of its SHA-256 hash. */
  private record Key(String digest, Match match, @Nullable Instant expires) {}

  private ApiKeys(final List<Key> keys, final boolean named) {
    // the same secret twice would give it whichever role comes last
    final Set<String> digests = new HashSet<>();
    for (final Key key : keys) {
      if (!digests.add(key.digest())) {
        throw new IllegalStateException(
            "The API key '" + key.match().name() + "' is configured more than once");
      }
    }
    this.keys = List.copyOf(keys);
    this.namedKeys = named;
  }

  /**
   * Read the keys from the configuration, plus an extra key of the given property (the MCP key).
   *
   * @param environment holds {@code yukta.api-key} and {@code yukta.api-keys}
   * @param extraKeyProperty property of one more key that may do everything, or null
   * @return the keys
   */
  public static ApiKeys read(
      final Environment environment, @Nullable final String extraKeyProperty) {
    final List<KeyEntry> entries =
        Binder.get(environment)
            // a misspelt property (session: for sessions:) must not quietly widen a key's reach
            .bind(
                "yukta.api-keys",
                Bindable.listOf(KeyEntry.class),
                new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
            .orElseGet(List::of);
    final List<Key> all = new ArrayList<>();
    add(all, DEFAULT_NAME, environment.getProperty("yukta.api-key"), ApiRole.ADMIN, Set.of(), null);
    if (extraKeyProperty != null) {
      add(all, "mcp", environment.getProperty(extraKeyProperty), ApiRole.ADMIN, Set.of(), null);
    }
    for (final KeyEntry entry : entries) {
      add(
          all,
          Objects.requireNonNullElse(entry.name(), "unnamed"),
          Objects.requireNonNull(entry.key(), "yukta.api-keys[].key is required"),
          Objects.requireNonNullElse(entry.role(), ApiRole.VIEWER),
          sessionsOf(entry),
          entry.expires());
    }
    return new ApiKeys(all, !entries.isEmpty());
  }

  /**
   * The sessions of a key: {@code ["*"]} says all of them on purpose; none listed also means all.
   */
  private static Set<String> sessionsOf(final KeyEntry entry) {
    if (entry.sessions().contains(ALL_SESSIONS)) {
      return Set.of();
    }
    if (entry.sessions().isEmpty() && entry.role() != ApiRole.ADMIN) {
      throw new IllegalStateException(
          "API key '"
              + entry.name()
              + "' lists no sessions; write sessions: ['*'] for every session or list them");
    }
    return Set.copyOf(entry.sessions());
  }

  /**
   * Keys made of one key that may do everything.
   *
   * @param apiKey the key, or empty for none
   * @return the keys
   */
  public static ApiKeys single(final String apiKey) {
    final List<Key> all = new ArrayList<>();
    add(all, DEFAULT_NAME, apiKey, ApiRole.ADMIN, Set.of(), null);
    return new ApiKeys(all, false);
  }

  private static void add(
      final List<Key> all,
      final String name,
      @Nullable final String key,
      final ApiRole role,
      final Set<String> sessions,
      @Nullable final Instant expires) {
    if (key == null || key.isBlank()) {
      return;
    }
    if (!key.startsWith(HASH_PREFIX) && key.length() < MIN_KEY_LENGTH) {
      log.atWarn()
          .addKeyValue("key", name)
          .log("This API key is shorter than {} characters and easy to guess", MIN_KEY_LENGTH);
    }
    final String digest = key.startsWith(HASH_PREFIX) ? hashed(name, key) : sha256(key);
    all.add(new Key(digest, new Match(name, role, sessions), expires));
  }

  /**
   * The key a request presents, as {@code Authorization: Bearer <key>} or {@code X-API-Key}.
   *
   * @param headers the request headers
   * @return the key, or empty if none
   */
  public static String presented(final HttpHeaders headers) {
    final String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
    final String key =
        // the scheme name is case-insensitive (RFC 7235)
        authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
            ? authorization.substring(BEARER.length())
            : headers.getFirst("X-API-Key");
    return key == null ? "" : key;
  }

  /**
   * Whether no key is configured.
   *
   * @return true if the API is open
   */
  public boolean isEmpty() {
    return keys.isEmpty();
  }

  /**
   * Whether the control commands of a request are recorded under the key name (named keys exist).
   *
   * @return true if the key name is the actor
   */
  public boolean named() {
    return namedKeys;
  }

  /**
   * Find the key a client presented. Compares the hashes of every key, so the time taken does not
   * tell which one is close.
   *
   * @param presented the key from the request
   * @return the match, or null if none
   */
  @Nullable
  public Match find(final String presented) {
    final byte[] digest = sha256(presented).getBytes(StandardCharsets.UTF_8);
    final Instant now = Instant.now();
    Match found = null;
    for (final Key key : keys) {
      if (MessageDigest.isEqual(digest, key.digest().getBytes(StandardCharsets.UTF_8))
          && (key.expires() == null || now.isBefore(key.expires()))) {
        found = key.match();
      }
    }
    return found;
  }

  /**
   * The hash of a {@code sha256:<hex>} key; anything but 64 hex digits is a configuration error.
   */
  private static String hashed(final String name, final String key) {
    final String hex = key.substring(HASH_PREFIX.length()).strip().toLowerCase(Locale.ROOT);
    if (!HEX_DIGEST.matcher(hex).matches()) {
      throw new IllegalArgumentException(
          "API key '" + name + "': a sha256: key must be 64 hexadecimal digits");
    }
    return hex;
  }

  private static String sha256(final String text) {
    return hash("SHA-256", text);
  }

  /** The hex of the hash of a text; every JVM has SHA-256, so that name never fails. */
  /* default */ static String hash(final String algorithm, final String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
