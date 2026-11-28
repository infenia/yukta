// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import com.infenia.yukta.service.metrics.ApiRequestMetrics;
import java.time.Duration;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Times every REST call for {@link ApiRequestMetrics}, by what it does (read, run, change). Event
 * streams are not timed: a stream lasts until the client leaves, which says nothing about the call.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRequestMetricsFilter implements WebFilter {

  private final ApiRequestMetrics metrics;

  /**
   * Creates the filter.
   *
   * @param metrics where the calls are recorded
   */
  public ApiRequestMetricsFilter(final ApiRequestMetrics metrics) {
    this.metrics = metrics;
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final ServerHttpRequest request = exchange.getRequest();
    final Optional<ApiRequestMetrics.Operation> operation =
        request.getHeaders().getAccept().contains(MediaType.TEXT_EVENT_STREAM)
            ? Optional.empty()
            : ApiRequestMetrics.classify(request.getMethod().name(), request.getPath().value());
    if (operation.isEmpty()) {
      return chain.filter(exchange);
    }
    final long start = System.nanoTime();
    return chain
        .filter(exchange)
        .doFinally(
            _ -> metrics.record(operation.get(), Duration.ofNanos(System.nanoTime() - start)));
  }
}
