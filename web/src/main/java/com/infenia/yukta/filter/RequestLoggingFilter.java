// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Web filter for logging HTTP requests and responses with timing information. */
@Slf4j
@Component
@NullMarked
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestLoggingFilter implements WebFilter {

  /** Default constructor. */
  public RequestLoggingFilter() {
    super();
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final long startTime = System.currentTimeMillis();
    final var request = exchange.getRequest();
    final String method = request.getMethod().toString();
    final String path = request.getPath().value();

    return chain
        .filter(exchange)
        .doFinally(
            signalType -> {
              final long duration = System.currentTimeMillis() - startTime;
              final var response = exchange.getResponse();
              final var statusCodeObj = response.getStatusCode();
              final int statusCode = statusCodeObj != null ? statusCodeObj.value() : 0;

              if (isRejection(statusCode)) {
                log.atWarn()
                    .log(
                        "{} {} -> {} in {}ms from {}",
                        method,
                        path,
                        statusCode,
                        duration,
                        remote(exchange));
              } else {
                log.info("{} {} -> {} in {}ms", method, path, statusCode, duration);
              }
            });
  }

  /** 400, 401, 403 and 429 are what the host and key filters answer; worth a warning. */
  private static boolean isRejection(final int status) {
    return status == 400 || status == 401 || status == 403 || status == 429;
  }

  private static String remote(final ServerWebExchange exchange) {
    final InetSocketAddress address = exchange.getRequest().getRemoteAddress();
    return address == null || address.getAddress() == null
        ? "unknown"
        : address.getAddress().getHostAddress();
  }
}
