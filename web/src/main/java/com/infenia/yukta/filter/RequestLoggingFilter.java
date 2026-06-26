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
