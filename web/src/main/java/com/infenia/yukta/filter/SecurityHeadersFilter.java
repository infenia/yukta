// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Adds the standard browser-hardening headers to every response: {@code X-Content-Type-Options},
 * {@code Referrer-Policy}, and {@code Strict-Transport-Security} when the request came over HTTPS
 * (directly or through a proxy that sets {@code X-Forwarded-Proto}). The Content-Security-Policy
 * stays with the page that needs it (see {@code SpaWebFluxConfig}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter implements WebFilter {

  /** One year, the usual HSTS lifetime. */
  private static final String HSTS = "max-age=31536000";

  /** Default constructor for Spring. */
  public SecurityHeadersFilter() {
    // Intentionally empty
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final boolean https =
        "https".equalsIgnoreCase(exchange.getRequest().getURI().getScheme())
            || "https"
                .equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto"));
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              final HttpHeaders headers = exchange.getResponse().getHeaders();
              headers.set("X-Content-Type-Options", "nosniff");
              headers.set("Referrer-Policy", "no-referrer");
              if (https) {
                headers.set("Strict-Transport-Security", HSTS);
              }
              return Mono.empty();
            });
    return chain.filter(exchange);
  }
}
