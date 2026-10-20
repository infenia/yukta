// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;

/**
 * The default {@link ApiAuthenticator}: the API keys of {@code yukta.api-key} and {@code
 * yukta.api-keys}, plus {@code yukta.mcp.api-key} for the MCP endpoint.
 */
public final class ApiKeyAuthenticator implements ApiAuthenticator {

  /** Keys for the REST API. */
  private final ApiKeys rest;

  /** Keys for the MCP endpoint. */
  private final ApiKeys mcp;

  private ApiKeyAuthenticator(final ApiKeys rest, final ApiKeys mcp) {
    this.rest = rest;
    this.mcp = mcp;
  }

  /**
   * Read the keys from the configuration.
   *
   * @param environment holds the API keys and {@code yukta.mcp.api-key}
   * @return the authenticator
   */
  public static ApiKeyAuthenticator read(final Environment environment) {
    return new ApiKeyAuthenticator(
        ApiKeys.read(environment, null), ApiKeys.read(environment, "yukta.mcp.api-key"));
  }

  /**
   * One key that may do everything, on both surfaces.
   *
   * @param apiKey the key, or empty to leave both open
   * @return the authenticator
   */
  public static ApiKeyAuthenticator single(final String apiKey) {
    return new ApiKeyAuthenticator(ApiKeys.single(apiKey), ApiKeys.single(apiKey));
  }

  @Override
  public boolean isOpen(final Scope scope) {
    return keys(scope).isEmpty();
  }

  @Override
  public boolean named() {
    return rest.named();
  }

  @Override
  public ApiKeys.@Nullable Match authenticate(final Scope scope, final HttpHeaders headers) {
    return keys(scope).find(ApiKeys.presented(headers));
  }

  private ApiKeys keys(final Scope scope) {
    return scope == Scope.MCP ? mcp : rest;
  }
}
