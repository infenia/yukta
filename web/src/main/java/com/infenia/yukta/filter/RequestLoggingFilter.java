// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Web filter for logging HTTP requests and responses with timing information. */
@Slf4j
@Component
@NullMarked
public class RequestLoggingFilter implements WebFilter {

  /** Default constructor. */
  public RequestLoggingFilter() {
    super();
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final long startTime = System.currentTimeMillis();
    @SuppressWarnings("PMD.LawOfDemeter")
    final var request = exchange.getRequest();
    final String method = request.getMethod().toString();
    final String path = request.getPath().value();

    log.debug("Incoming request - method: {}, path: {}", method, path);

    return chain
        .filter(exchange)
        .doFinally(
            signalType -> {
              final long duration = System.currentTimeMillis() - startTime;
              @SuppressWarnings("PMD.LawOfDemeter")
              final var response = exchange.getResponse();
              final var statusCodeObj = response.getStatusCode();
              final int statusCode = statusCodeObj != null ? statusCodeObj.value() : 0;

              log.debug(
                  "Outgoing response - method: {}, path: {}, statusCode: {}, duration: {}ms",
                  method,
                  path,
                  statusCode,
                  duration);
            });
  }
}
