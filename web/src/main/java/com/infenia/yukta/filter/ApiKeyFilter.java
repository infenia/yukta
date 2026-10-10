// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Requires an API key on every {@code /api/**} request when {@code yukta.api-key} is set, as {@code
 * Authorization: Bearer <key>} or {@code X-API-Key: <key>}. With no key configured the API stays
 * open, which is safe only because the server listens on loopback by default.
 */
@Component
@NullMarked
@Order(Ordered.HIGHEST_PRECEDENCE)
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
public class ApiKeyFilter implements WebFilter {

  /** Header that may carry the key instead of the Authorization header. */
  private static final String API_KEY_HEADER = "X-API-Key";

  /** Prefix of the Authorization header value. */
  private static final String BEARER = "Bearer ";

  /** Key clients must present; empty disables the check. */
  private final byte[] apiKey;

  /**
   * Create the filter.
   *
   * @param apiKey the required key, or empty to leave the API open
   */
  public ApiKeyFilter(@Value("${yukta.api-key:}") final String apiKey) {
    this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    if (apiKey.length == 0 || !exchange.getRequest().getPath().value().startsWith("/api/")) {
      return chain.filter(exchange);
    }
    final HttpHeaders headers = exchange.getRequest().getHeaders();
    final String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
    final String presented =
        authorization != null && authorization.startsWith(BEARER)
            ? authorization.substring(BEARER.length())
            : headers.getFirst(API_KEY_HEADER);
    if (presented != null
        && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), apiKey)) {
      return chain.filter(exchange);
    }
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    return exchange.getResponse().setComplete();
  }
}
