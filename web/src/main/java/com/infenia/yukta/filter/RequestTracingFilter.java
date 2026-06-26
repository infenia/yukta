/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
