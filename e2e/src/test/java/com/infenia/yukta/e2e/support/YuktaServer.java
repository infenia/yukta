// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import com.infenia.yukta.security.ApiRole;
import java.util.Objects;
import lombok.NoArgsConstructor;
import lombok.experimental.UtilityClass;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * The server every e2e test talks to, and the injector of {@link Api} and {@link Scenarios}.
 *
 * <p>With {@code -Pyukta.e2e.url} (and {@code -Pyukta.e2e.key}, an ADMIN key) it targets that
 * server. Otherwise it starts {@code YuktaApplication} once per JVM on a random port with every
 * plugin, the commands {@code sh} and {@code echo}, in-memory stores and one key per role; the
 * JVM's shutdown hook stops it.
 */
@NoArgsConstructor
public final class YuktaServer implements BeforeAllCallback, ParameterResolver {

  private static final String URL_PROPERTY = "yukta.e2e.url";
  private static final String KEY_PROPERTY = "yukta.e2e.key";

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
    return Holder.SERVER.baseUrl();
  }

  /**
   * The API key of a role.
   *
   * @param role the role
   * @return its key; for an external server only ADMIN has one
   * @throws IllegalStateException for another role on an external server (use {@link InJvmOnly})
   */
  public static String key(final ApiRole role) {
    return Holder.SERVER.key(role);
  }

  /**
   * An OPERATOR key limited to {@link DedicatedServer#SCOPED_SESSION}.
   *
   * @return the key
   * @throws IllegalStateException on an external server (use {@link InJvmOnly})
   */
  public static String scopedKey() {
    return Holder.SERVER.scopedKey();
  }

  /** Starts the server on first use; class initialization makes that once per JVM. */
  @UtilityClass
  private static final class Holder {
    // never closed: SpringApplication's shutdown hook stops the in-JVM server with the JVM
    private static final DedicatedServer SERVER =
        external()
            ? DedicatedServer.external(
                System.getProperty(URL_PROPERTY), System.getProperty(KEY_PROPERTY))
            : DedicatedServer.start(true);
  }

  @Override
  public void beforeAll(final ExtensionContext context) {
    // starts the in-JVM server before the first test, so its start-up is not timed as a test
    Objects.requireNonNull(baseUrl());
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
