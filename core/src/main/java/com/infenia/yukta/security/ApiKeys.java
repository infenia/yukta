// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;

/**
 * The API keys of the server, shared by the REST and the MCP filter. Keys come from {@code
 * yukta.api-key} (one key that may do everything) and {@code yukta.api-keys} (named keys with a
 * role and an optional list of sessions). A configured key is plain text or {@code sha256:<hex>}
 * (the hash of the key), so the file need not hold the secret; listing several keys at once lets a
 * key be rotated without downtime.
 */
public final class ApiKeys {

  /** The Authorization scheme prefix. */
  private static final String BEARER = "Bearer ";

  /** Prefix of a key given as a hash. */
  private static final String HASH_PREFIX = "sha256:";

  /** A SHA-256 digest in hex. */
  private static final Pattern HEX_DIGEST = Pattern.compile("[0-9a-f]{64}");

  /** Name the single {@code yukta.api-key} goes by. */
  private static final String DEFAULT_NAME = "default";

  /** The configured keys. */
  private final List<Key> keys;

  /** Whether named keys exist, so a key name is the actor of a request. */
  private final boolean namedKeys;

  /** A key as written in the configuration. */
  public record KeyEntry(String name, String key, ApiRole role, List<String> sessions) {

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
  private record Key(String digest, Match match) {}

  private ApiKeys(final List<Key> keys, final boolean named) {
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
            .bind("yukta.api-keys", Bindable.listOf(KeyEntry.class))
            .orElseGet(List::of);
    final List<Key> all = new ArrayList<>();
    add(all, DEFAULT_NAME, environment.getProperty("yukta.api-key"), ApiRole.ADMIN, Set.of());
    if (extraKeyProperty != null) {
      add(all, "mcp", environment.getProperty(extraKeyProperty), ApiRole.ADMIN, Set.of());
    }
    for (final KeyEntry entry : entries) {
      add(
          all,
          Objects.requireNonNullElse(entry.name(), "unnamed"),
          Objects.requireNonNull(entry.key(), "yukta.api-keys[].key is required"),
          Objects.requireNonNullElse(entry.role(), ApiRole.VIEWER),
          Set.copyOf(entry.sessions()));
    }
    return new ApiKeys(all, !entries.isEmpty());
  }

  /**
   * Keys made of one key that may do everything.
   *
   * @param apiKey the key, or empty for none
   * @return the keys
   */
  public static ApiKeys single(final String apiKey) {
    final List<Key> all = new ArrayList<>();
    add(all, DEFAULT_NAME, apiKey, ApiRole.ADMIN, Set.of());
    return new ApiKeys(all, false);
  }

  private static void add(
      final List<Key> all,
      final String name,
      @Nullable final String key,
      final ApiRole role,
      final Set<String> sessions) {
    if (key == null || key.isBlank()) {
      return;
    }
    final String digest = key.startsWith(HASH_PREFIX) ? hashed(name, key) : sha256(key);
    all.add(new Key(digest, new Match(name, role, sessions)));
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
    Match found = null;
    for (final Key key : keys) {
      if (MessageDigest.isEqual(digest, key.digest().getBytes(StandardCharsets.UTF_8))) {
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
