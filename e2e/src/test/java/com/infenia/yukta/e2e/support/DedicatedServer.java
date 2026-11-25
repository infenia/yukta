// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import com.infenia.yukta.YuktaApplication;
import com.infenia.yukta.security.ApiRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A server a test class starts for itself, when it needs settings the shared one does not have: no
 * API keys, or a low rate limit. Close it after the class. {@link YuktaServer} uses the same class
 * for the shared server.
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class DedicatedServer implements AutoCloseable {

  /** The session the {@link #scopedKey()} is limited to. */
  public static final String SCOPED_SESSION = "e2e-scope-a";

  private final String url;
  private final Map<ApiRole, String> keys;
  private final String scopedKey;
  private final ConfigurableApplicationContext context;

  private DedicatedServer(
      final String url,
      final Map<ApiRole, String> keys,
      final String scopedKey,
      final ConfigurableApplicationContext context) {
    this.url = url;
    this.keys = keys;
    this.scopedKey = scopedKey;
    this.context = context;
  }

  /**
   * Starts the app in this JVM on a random port with every plugin, the commands {@code sh} and
   * {@code echo} and in-memory stores (PostgreSQL ones with {@code -Pyukta.persistence=postgres},
   * see {@link PostgresDatabase}).
   *
   * @param withKeys true for one key per role (each reaching every session) and an OPERATOR key
   *     limited to {@link #SCOPED_SESSION}; false for no keys at all
   * @param properties more settings as {@code name=value}, for example {@code
   *     yukta.mcp.heavy-rate-limit-per-minute=2}
   * @return the running server
   */
  public static DedicatedServer start(final boolean withKeys, final String... properties) {
    final List<String> args =
        new ArrayList<>(
            List.of(
                "--server.port=0", "--server.address=127.0.0.1", "--yukta.plugins.enabled=all"));
    final Map<ApiRole, String> keys = new ConcurrentHashMap<>();
    String scoped = null;
    if (withKeys) {
      for (final ApiRole role : ApiRole.values()) {
        keys.put(role, addKey(args, role.name().toLowerCase(Locale.ROOT), role, "*"));
      }
      scoped = addKey(args, "scoped", ApiRole.OPERATOR, SCOPED_SESSION);
    }
    if (PostgresDatabase.enabled()) {
      args.addAll(PostgresDatabase.args());
    }
    for (final String property : properties) {
      args.add("--" + property);
    }
    final ConfigurableApplicationContext context =
        new SpringApplicationBuilder(YuktaApplication.class)
            .properties("yukta.process.allowed-commands=sh,echo")
            .run(args.toArray(String[]::new));
    return new DedicatedServer(
        "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"),
        keys,
        scoped,
        context);
  }

  /**
   * Targets a server that is already running.
   *
   * @param url its base URL
   * @param adminKey an ADMIN key, or null or blank if it has none to give
   * @return a handle whose {@link #close()} does nothing
   */
  public static DedicatedServer external(final String url, final String adminKey) {
    final Map<ApiRole, String> keys = new ConcurrentHashMap<>();
    if (adminKey != null && !adminKey.isBlank()) {
      keys.put(ApiRole.ADMIN, adminKey);
    }
    return new DedicatedServer(url.replaceAll("/+$", ""), keys, null, null);
  }

  private static String addKey(
      final List<String> args, final String name, final ApiRole role, final String sessions) {
    final String key = "e2e-" + name + "-" + UUID.randomUUID();
    final long index = args.stream().filter(a -> a.contains(".name=")).count();
    final String prefix = "--yukta.api-keys[" + index + "].";
    args.add(prefix + "name=e2e-" + name);
    args.add(prefix + "key=" + key);
    args.add(prefix + "sessions=" + sessions);
    args.add(prefix + "role=" + role.name());
    return key;
  }

  /**
   * The base URL.
   *
   * @return for example {@code http://127.0.0.1:41234}, without a trailing slash
   */
  public String baseUrl() {
    return url;
  }

  /**
   * The key of a role.
   *
   * @param role the role
   * @return its key
   * @throws IllegalStateException if the server has none (no keys, or an external server)
   */
  public String key(final ApiRole role) {
    final String key = keys.get(role);
    if (key == null) {
      throw new IllegalStateException(
          "No " + role + " key for " + url + "; annotate the test with @InJvmOnly");
    }
    return key;
  }

  /**
   * The OPERATOR key limited to {@link #SCOPED_SESSION}.
   *
   * @return the key
   * @throws IllegalStateException if the server has none
   */
  public String scopedKey() {
    if (scopedKey == null) {
      throw new IllegalStateException("No scoped key for " + url + "; annotate with @InJvmOnly");
    }
    return scopedKey;
  }

  /**
   * Stops this server for good, for a test of a restart or of a standby taking over (the database
   * and the other servers stay). Unlike {@link #close()} it really closes the application context,
   * which also stops the HTTP of every other server in this JVM: only a test that runs in a JVM of
   * its own (tag {@code own-jvm}) may call it.
   */
  public void stop() {
    if (context != null) {
      context.close();
    }
  }

  /**
   * Does nothing: the server stays up until the JVM exits. Closing one application context in a JVM
   * that holds others stopped the shared server too (its Reactor resources are process-wide).
   */
  @Override
  public void close() {
    // intentionally empty; the shutdown hook of each SpringApplication stops its context
  }
}
