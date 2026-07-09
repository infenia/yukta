// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Web filter for adding request tracing headers (correlation IDs) for distributed tracing. */
@Component
@NullMarked
public class RequestTracingFilter implements WebFilter {

  /** Header name for request ID. */
  private static final String X_REQUEST_ID = "X-Request-ID";

  /** Header name for correlation ID. */
  private static final String X_CORRELATION_ID = "X-Correlation-ID";

  /** Default constructor. */
  public RequestTracingFilter() {
    super();
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    @SuppressWarnings("PMD.LawOfDemeter")
    final ServerHttpRequest request = exchange.getRequest();
    final HttpHeaders requestHeaders = request.getHeaders();

    final String requestId = requestHeaders.getFirst(X_REQUEST_ID);
    final String correlationId = requestHeaders.getFirst(X_CORRELATION_ID);

    final String finalRequestId = requestId != null ? requestId : UUID.randomUUID().toString();
    final String finalCorrelationId = correlationId != null ? correlationId : finalRequestId;

    @SuppressWarnings("PMD.LawOfDemeter")
    final ServerHttpResponse response = exchange.getResponse();
    final HttpHeaders responseHeaders = response.getHeaders();

    responseHeaders.add(X_REQUEST_ID, finalRequestId);
    responseHeaders.add(X_CORRELATION_ID, finalCorrelationId);

    return chain.filter(exchange);
  }
}
