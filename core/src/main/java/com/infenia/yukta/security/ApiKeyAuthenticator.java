// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;

/** The default {@link ApiAuthenticator}: the API keys of {@code yukta.api-keys}. */
public final class ApiKeyAuthenticator implements ApiAuthenticator {

  /** The keys, for the REST API and the MCP endpoint alike. */
  private final ApiKeys keys;

  private ApiKeyAuthenticator(final ApiKeys keys) {
    this.keys = keys;
  }

  /**
   * Read the keys from the configuration.
   *
   * @param environment holds {@code yukta.api-keys}
   * @return the authenticator
   */
  public static ApiKeyAuthenticator read(final Environment environment) {
    return new ApiKeyAuthenticator(ApiKeys.read(environment));
  }

  @Override
  public boolean isOpen(final Scope scope) {
    return keys.isEmpty();
  }

  @Override
  public ApiKeys.@Nullable Match authenticate(final Scope scope, final HttpHeaders headers) {
    return keys.find(ApiKeys.presented(headers));
  }
}
