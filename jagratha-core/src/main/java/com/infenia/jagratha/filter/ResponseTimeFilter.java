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
package com.infenia.jagratha.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Filter to add X-Response-Time header to all responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@SuppressWarnings("PMD.LawOfDemeter")
public class ResponseTimeFilter implements WebFilter {

  private static final String X_RESPONSE_TIME = "X-Response-Time";

  /** Default constructor. */
  public ResponseTimeFilter() {
    super();
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final long startTime = System.currentTimeMillis();
    exchange
        .getResponse()
        .beforeCommit(
            () -> {
              final long duration = System.currentTimeMillis() - startTime;
              exchange.getResponse().getHeaders().add(X_RESPONSE_TIME, duration + "ms");
              return Mono.empty();
            });
    return chain.filter(exchange);
  }
}
