// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guards the MCP endpoint ({@value #ENDPOINT}). When {@code yukta.mcp.api-key} is set, requests
 * must carry it as {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}. Also keeps the
 * former {@value #LEGACY_ENDPOINT} path working by rewriting it to the endpoint.
 */
@Component
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpEndpointFilter implements WebFilter {

  /** Path of the streamable-HTTP MCP endpoint. */
  private static final String ENDPOINT = "/mcp";

  /** Former endpoint name, kept as an alias for clients that are already configured. */
  private static final String LEGACY_ENDPOINT = "/sse";

  /** Key clients must present; empty disables the check. */
  private final String apiKey;

  /**
   * Create the filter.
   *
   * @param apiKey the required key, or empty to leave the endpoint open
   */
  public McpEndpointFilter(@Value("${yukta.mcp.api-key:}") final String apiKey) {
    this.apiKey = apiKey;
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final String path = exchange.getRequest().getPath().value();
    final boolean legacy = LEGACY_ENDPOINT.equals(path);
    if (!legacy && !ENDPOINT.equals(path)) {
      return chain.filter(exchange);
    }
    if (!authorized(exchange)) {
      exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
      return exchange.getResponse().setComplete();
    }
    return chain.filter(
        legacy ? exchange.mutate().request(request -> request.path(ENDPOINT)).build() : exchange);
  }

  private boolean authorized(final ServerWebExchange exchange) {
    if (apiKey.isBlank()) {
      return true;
    }
    final var headers = exchange.getRequest().getHeaders();
    final String bearer = headers.getFirst("Authorization");
    final String presented =
        bearer != null && bearer.startsWith("Bearer ")
            ? bearer.substring("Bearer ".length())
            : headers.getFirst("X-API-Key");
    return presented != null
        && MessageDigest.isEqual(
            presented.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8));
  }
}
