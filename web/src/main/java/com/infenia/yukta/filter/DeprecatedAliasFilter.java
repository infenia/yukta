// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Marks calls to the deprecated {@code /api/workflow/...} execution aliases with a {@code
 * Deprecation: true} header and logs each one, so the operator can see who still uses them before
 * they are removed. The current routes under that prefix ({@code start}, {@code
 * {sessionId}/{workflowId}/stop}, {@code {sessionId}/history}) are not aliases.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class DeprecatedAliasFilter implements WebFilter {

  /** The alias families listed in the API overview. */
  private static final Pattern ALIAS =
      Pattern.compile(
          "^/api/workflow/(executions/.+|[^/]+/status/.+|[^/]+/[^/]+/(pause|resume|recover)"
              + "|[^/]+/[^/]+/node/.+)$");

  /** Default constructor for Spring. */
  public DeprecatedAliasFilter() {
    // Intentionally empty
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final String path = exchange.getRequest().getPath().value();
    if (ALIAS.matcher(path).matches()) {
      exchange.getResponse().getHeaders().set("Deprecation", "true");
      log.warn(
          "Deprecated alias {} {} from {}",
          exchange.getRequest().getMethod(),
          path,
          exchange.getRequest().getHeaders().getFirst("User-Agent"));
    }
    return chain.filter(exchange);
  }
}
