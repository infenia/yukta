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
import lombok.NoArgsConstructor;
import lombok.experimental.UtilityClass;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The server every e2e test talks to, and the injector of {@link Api} and {@link Scenarios}.
 *
 * <p>With {@code -Pyukta.e2e.url} (and {@code -Pyukta.e2e.key}, an ADMIN key) it targets that
 * server. Otherwise it starts {@link YuktaApplication} once per JVM on a random port with every
 * plugin, the commands {@code sh} and {@code echo}, in-memory stores and one key per role; the
 * JVM's shutdown hook stops it.
 */
@NoArgsConstructor
public final class YuktaServer implements BeforeAllCallback, ParameterResolver {

  private static final String URL_PROPERTY = "yukta.e2e.url";
  private static final String KEY_PROPERTY = "yukta.e2e.key";

  /** What every test talks to: the URL and the key of each role the server was given. */
  private record Target(String url, Map<ApiRole, String> keys) {}

  /**
   * Whether the server is an external URL rather than the app started in this JVM.
   *
   * @return true when {@code yukta.e2e.url} is set
   */
  public static boolean external() {
    final String url = System.getProperty(URL_PROPERTY);
    return url != null && !url.isBlank();
  }

  /**
   * The server's base URL, for example {@code http://127.0.0.1:41234}. Starts the in-JVM server on
   * first use.
   *
   * @return the URL without a trailing slash
   */
  public static String baseUrl() {
    return target().url();
  }

  /**
   * The API key of a role.
   *
   * @param role the role
   * @return its key; for an external server only ADMIN has one
   * @throws IllegalStateException for another role on an external server (use {@link InJvmOnly})
   */
  public static String key(final ApiRole role) {
    final String key = target().keys().get(role);
    if (key == null) {
      throw new IllegalStateException(
          "No " + role + " key for the external server; annotate the test with @InJvmOnly");
    }
    return key;
  }

  /** Starts the server on first use; class initialization makes that once per JVM. */
  @UtilityClass
  private static final class Holder {
    private static final Target TARGET = external() ? externalTarget() : start();
  }

  private static Target target() {
    return Holder.TARGET;
  }

  private static Target externalTarget() {
    final String key = System.getProperty(KEY_PROPERTY);
    final Map<ApiRole, String> keys =
        key == null || key.isBlank() ? Map.of() : Map.of(ApiRole.ADMIN, key);
    return new Target(System.getProperty(URL_PROPERTY).replaceAll("/+$", ""), keys);
  }

  @SuppressWarnings("PMD.CloseResource") // lives until the JVM's shutdown hook stops it
  private static Target start() {
    final List<String> args =
        new ArrayList<>(
            List.of(
                "--server.port=0", "--server.address=127.0.0.1", "--yukta.plugins.enabled=all"));
    final Map<ApiRole, String> keys = new ConcurrentHashMap<>();
    for (final ApiRole role : ApiRole.values()) {
      final String name = "e2e-" + role.name().toLowerCase(Locale.ROOT);
      final String prefix = "--yukta.api-keys[" + keys.size() + "].";
      keys.put(role, name + "-" + UUID.randomUUID());
      args.add(prefix + "name=" + name);
      args.add(prefix + "key=" + keys.get(role));
      args.add(prefix + "sessions=*");
      args.add(prefix + "role=" + role.name());
    }
    final ConfigurableApplicationContext context =
        new SpringApplicationBuilder(YuktaApplication.class)
            .properties("yukta.process.allowed-commands=sh,echo")
            .run(args.toArray(String[]::new));
    return new Target(
        "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"), keys);
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    target();
  }

  @Override
  public boolean supportsParameter(
      final ParameterContext parameter, final ExtensionContext context) {
    final Class<?> type = parameter.getParameter().getType();
    return type == Api.class || type == Scenarios.class;
  }

  @Override
  public Object resolveParameter(final ParameterContext parameter, final ExtensionContext context) {
    final Api api = Api.forRole(ApiRole.ADMIN);
    if (parameter.getParameter().getType() == Api.class) {
      return api;
    }
    if (parameter.getParameter().getType() == Scenarios.class) {
      // one per test; the store closes it after the test, which deletes what it created
      return context
          .getStore(ExtensionContext.Namespace.create(YuktaServer.class, context.getUniqueId()))
          .computeIfAbsent("scenarios", key -> new Scenarios(api), Scenarios.class);
    }
    throw new ParameterResolutionException("Unsupported parameter " + parameter);
  }
}
